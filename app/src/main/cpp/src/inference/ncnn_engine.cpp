#include "ncnn_engine.h"
#include <ncnn/mat.h>

NcnnEngine::NcnnEngine() = default;

NcnnEngine::~NcnnEngine() {
    release();
}

bool NcnnEngine::init(const char* model_path) {
    release();

    std::string path_str(model_path);
    std::string bin_path = path_str.substr(0, path_str.size() - 6) + ".bin";

    m_net = new ncnn::Net();
    m_net->opt.use_vulkan_compute = false;
    m_net->opt.num_threads = m_cpu_threads;

    // Register PNNX/ONNX layer name mappings for quantized models
    m_net->register_custom_layer("QuantizeLinear",
        [](void*) -> ncnn::Layer* { return ncnn::create_layer("Quantize"); },
        [](ncnn::Layer* layer, void*) { delete layer; });
    m_net->register_custom_layer("DequantizeLinear",
        [](void*) -> ncnn::Layer* { return ncnn::create_layer("Dequantize"); },
        [](ncnn::Layer* layer, void*) { delete layer; });
    m_net->register_custom_layer("pnnx.Expression",
        [](void*) -> ncnn::Layer* { return ncnn::create_layer("MemoryData"); },
        [](ncnn::Layer* layer, void*) { delete layer; });

    int ret_param = m_net->load_param(path_str.c_str());
    int ret_model = m_net->load_model(bin_path.c_str());

    if (ret_param != 0 || ret_model != 0) {
        LOGE("NCNN model load failed: param=%d, model=%d", ret_param, ret_model);
        delete m_net;
        m_net = nullptr;
        return false;
    }

    m_input_nhwc = false;  // NCNN uses NCHW internally
    m_initialized = true;

    LOGD("NCNN initialized: %s, input=%dx%d", path_str.c_str(), m_input_width, m_input_height);
    return true;
}

void NcnnEngine::release() {
    if (m_net) {
        delete m_net;
        m_net = nullptr;
    }
    m_initialized = false;
}

std::vector<Detection> NcnnEngine::detect(
    uint8_t* src,
    int offsetX, int offsetY,
    int regionWidth, int regionHeight,
    int screenWidth, int screenHeight,
    int rowStride, int pixelStride)
{
    if (!m_net || regionWidth <= 0 || regionHeight <= 0) {
        return {};
    }

    int H = m_input_height;
    int W = m_input_width;

    // Determine pixel format
    int pixel_type;
    if (pixelStride == 4) {
        pixel_type = ncnn::Mat::PIXEL_RGBA2BGR;
    } else if (pixelStride == 3) {
        pixel_type = ncnn::Mat::PIXEL_RGB2BGR;
    } else {
        LOGE("Unsupported pixelStride: %d", pixelStride);
        return {};
    }

    const unsigned char* src_ptr = src + offsetY * rowStride + offsetX * pixelStride;

    ncnn::Mat in;
    try {
        in = ncnn::Mat::from_pixels_resize(
            src_ptr, pixel_type,
            regionWidth, regionHeight, rowStride,
            W, H);
    } catch (const std::exception& e) {
        LOGE("NCNN from_pixels_resize failed: %s", e.what());
        return {};
    }

    if (in.empty()) return {};

    // Normalize: /255.0
    const float mean_vals[3] = {0.f, 0.f, 0.f};
    const float norm_vals[3] = {1/255.f, 1/255.f, 1/255.f};
    in.substract_mean_normalize(mean_vals, norm_vals);

    long long t1 = getTimeUs();

    ncnn::Extractor ex = m_net->create_extractor();

    // Try common input names
    int ret_input = ex.input("in0", in);
    if (ret_input != 0) {
        ret_input = ex.input("images", in);
        if (ret_input != 0) {
            ret_input = ex.input("input", in);
            if (ret_input != 0) {
                LOGE("NCNN input failed for all names, ret=%d", ret_input);
                return {};
            }
        }
    }

    ncnn::Mat out;
    int ret_out = ex.extract("out0", out);
    if (ret_out != 0) {
        LOGE("NCNN extract out0 failed: %d", ret_out);
        return {};
    }

    long long t2 = getTimeUs();
    LOGD("NCNN Inference: %lld us", t2 - t1);

    float invW = 1.0f / screenWidth;
    float invH = 1.0f / screenHeight;

    std::vector<Detection> detections;

    // Check format: official YOLOv8 (2D, w > 64) vs legacy
    const int reg_max_1 = 16;
    bool is_official_format = (out.dims == 2 && out.w > reg_max_1 * 4);

    if (is_official_format) {
        detections = parseOfficialFormat(out, W, H, offsetX, offsetY, regionWidth, regionHeight, invW, invH);
    } else {
        detections = parseLegacyFormat(ex, out, W, H, offsetX, offsetY, regionWidth, regionHeight, invW, invH);
    }

    LOGD("NCNN Raw: %zu, After NMS: %zu", detections.size(), detections.size());

    auto finalDetections = nms(detections, 0.45f);
    return finalDetections;
}

std::vector<Detection> NcnnEngine::parseOfficialFormat(
    const ncnn::Mat& out, int W, int H,
    int offsetX, int offsetY,
    int regionWidth, int regionHeight,
    float invW, float invH)
{
    const int reg_max_1 = 16;
    int num_anchors = out.h;
    int feature_dim = out.w;
    int num_class = feature_dim - reg_max_1 * 4;
    if (num_class < 1) num_class = 1;
    m_num_classes = num_class;

    std::vector<int> strides = {8, 16, 32};
    std::vector<Detection> detections;

    int pred_row_offset = 0;
    for (size_t s = 0; s < strides.size(); s++) {
        int stride = strides[s];
        int num_grid_x = W / stride;
        int num_grid_y = H / stride;
        int num_grid = num_grid_x * num_grid_y;

        for (int i = 0; i < num_grid; i++) {
            int row_idx = pred_row_offset + i;
            const float* pred_row = out.row(row_idx);

            // Find max class score
            int label = -1;
            float score = -1e9f;
            for (int c = 0; c < num_class; c++) {
                float s = pred_row[reg_max_1 * 4 + c];
                if (s > score) {
                    score = s;
                    label = c;
                }
            }
            score = sigmoid(score);

            if (score < m_conf_thresh) continue;

            // DFL bbox decode
            float pred_ltrb[4];
            for (int k = 0; k < 4; k++) {
                float softmax_sum = 0.f;
                float softmax_max = -1e9f;
                for (int l = 0; l < reg_max_1; l++) {
                    float v = pred_row[k * reg_max_1 + l];
                    if (v > softmax_max) softmax_max = v;
                }
                for (int l = 0; l < reg_max_1; l++) {
                    softmax_sum += expf(pred_row[k * reg_max_1 + l] - softmax_max);
                }
                float dis = 0.f;
                for (int l = 0; l < reg_max_1; l++) {
                    float prob = expf(pred_row[k * reg_max_1 + l] - softmax_max) / softmax_sum;
                    dis += l * prob;
                }
                pred_ltrb[k] = dis * stride;
            }

            int grid_x = i % num_grid_x;
            int grid_y = i / num_grid_x;
            float pb_cx = (grid_x + 0.5f) * stride;
            float pb_cy = (grid_y + 0.5f) * stride;

            float x0 = pb_cx - pred_ltrb[0];
            float y0 = pb_cy - pred_ltrb[1];
            float x1 = pb_cx + pred_ltrb[2];
            float y1 = pb_cy + pred_ltrb[3];

            x0 /= W; y0 /= H;
            x1 /= W; y1 /= H;

            if (x0 < 0) x0 = 0;
            if (y0 < 0) y0 = 0;
            if (x1 > 1) x1 = 1;
            if (y1 > 1) y1 = 1;
            if (x1 <= x0 || y1 <= y0) continue;

            detections.push_back({
                (offsetX + x0 * regionWidth) * invW,
                (offsetY + y0 * regionHeight) * invH,
                (offsetX + x1 * regionWidth) * invW,
                (offsetY + y1 * regionHeight) * invH,
                score,
                (float)label
            });
        }
        pred_row_offset += num_grid;
    }
    return detections;
}

std::vector<Detection> NcnnEngine::parseLegacyFormat(
    ncnn::Extractor& ex,
    const ncnn::Mat& out0_in, int W, int H,
    int offsetX, int offsetY,
    int regionWidth, int regionHeight,
    float invW, float invH)
{
    ncnn::Mat out0 = out0_in;
    ncnn::Mat out1, out2;
    int ret1 = ex.extract("out1", out1);
    int ret2 = ex.extract("out2", out2);

    int channels, total_anchors;
    bool is_legacy_3output = (ret1 == 0 && ret2 == 0);
    bool is_int8_output = (out0.elemsize == 1);

    if (is_legacy_3output) {
        channels = out0.c;
        total_anchors = out0.w * out0.h + out1.w * out1.h + out2.w * out2.h;
    } else {
        if (out0.dims == 3) {
            channels = out0.c;
            total_anchors = out0.w * out0.h;
        } else if (out0.dims == 2) {
            channels = out0.h;
            total_anchors = out0.w;
        } else {
            LOGE("NCNN unsupported output dims: %d", out0.dims);
            return {};
        }
    }

    int num_class = channels - 4;
    if (num_class < 1) num_class = 1;
    m_num_classes = num_class;

    const int8_t zero_point = -128;
    const float scale = 0.004291f;

    auto get_value = [&](int channel, int anchor) -> float {
        if (is_int8_output) {
            int8_t val;
            if (is_legacy_3output) {
                int offset = 0;
                if (anchor < out0.w * out0.h) {
                    val = ((const int8_t*)out0.data)[channel * out0.w * out0.h + anchor];
                } else if (anchor < out0.w * out0.h + out1.w * out1.h) {
                    offset = out0.w * out0.h;
                    val = ((const int8_t*)out1.data)[channel * out1.w * out1.h + (anchor - offset)];
                } else {
                    offset = out0.w * out0.h + out1.w * out1.h;
                    val = ((const int8_t*)out2.data)[channel * out2.w * out2.h + (anchor - offset)];
                }
            } else {
                if (out0.dims == 3) {
                    val = ((const int8_t*)out0.data)[channel * out0.w * out0.h + anchor];
                } else {
                    val = ((const int8_t*)out0.data)[channel * out0.w + anchor];
                }
            }
            return (val - zero_point) * scale;
        } else {
            if (is_legacy_3output) {
                int offset = 0;
                if (anchor < out0.w * out0.h) {
                    return ((const float*)out0.data)[channel * out0.w * out0.h + anchor];
                } else if (anchor < out0.w * out0.h + out1.w * out1.h) {
                    offset = out0.w * out0.h;
                    return ((const float*)out1.data)[channel * out1.w * out1.h + (anchor - offset)];
                } else {
                    offset = out0.w * out0.h + out1.w * out1.h;
                    return ((const float*)out2.data)[channel * out2.w * out2.h + (anchor - offset)];
                }
            } else {
                if (out0.dims == 3) {
                    return ((const float*)out0.data)[channel * out0.w * out0.h + anchor];
                } else {
                    return ((const float*)out0.data)[channel * out0.w + anchor];
                }
            }
        }
    };

    std::vector<Detection> detections;
    for (int i = 0; i < total_anchors; ++i) {
        float cx = get_value(0, i);
        float cy = get_value(1, i);
        float bw = get_value(2, i);
        float bh = get_value(3, i);

        float score;
        int classId = 0;

        if (num_class <= 1) {
            score = get_value(4, i);
        } else {
            float maxProb = -1e9f;
            int maxClass = 0;
            for (int c = 0; c < num_class; c++) {
                float prob = get_value(4 + c, i);
                if (prob > maxProb) { maxProb = prob; maxClass = c; }
            }
            score = maxProb;
            classId = maxClass;
        }

        if (score < m_conf_thresh) continue;
        if (bw <= 0 || bh <= 0) continue;
        if (cx < 0 || cx > 1 || cy < 0 || cy > 1) continue;

        float hw = bw * 0.5f, hh = bh * 0.5f;
        detections.push_back({
            (offsetX + (cx - hw) * regionWidth) * invW,
            (offsetY + (cy - hh) * regionHeight) * invH,
            (offsetX + (cx + hw) * regionWidth) * invW,
            (offsetY + (cy + hh) * regionHeight) * invH,
            score,
            (float)classId
        });
    }
    return detections;
}
