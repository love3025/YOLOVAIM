package io.github.love3025.yolovaim.manager

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream

/**
 * 用户导入模型的注册表。
 *
 * 本项目不内置任何模型，也不针对任何特定应用：所有检测模型由用户自行导入。
 * 模型文件存放在 `filesDir/models/`，元数据记录在 `filesDir/models/registry.json`。
 *
 * 支持的格式：
 *   - TFLite (`.tflite`)        单文件，导入时自动探测张量形状
 *   - NCNN   (`.param` + `.bin`) 成对导入，输入尺寸从 `.param` 文本解析
 *
 * 探测出的元数据只是默认值，用户可以在导入对话框里逐项改写——包括类别名，
 * 因为类别的语义完全取决于用户自己训练的数据集，本应用不做任何假设。
 */
object ModelRepository {

    private const val TAG = "ModelRepository"
    private const val DIR_NAME = "models"
    private const val REGISTRY_NAME = "registry.json"

    /** 一个已导入的模型。除 [id] / [fileName] 外全部可由用户改写。 */
    data class ImportedModel(
        val id: String,
        val fileName: String,
        var displayName: String,
        var precision: String,
        var inputSize: Int,
        var outputSize: Int,
        var description: String,
        var classes: MutableMap<Int, String>
    )

    /** 从模型文件里探测出来的元数据；任何一项探测失败都退化成 0 / UNKNOWN。 */
    data class Probe(
        val inputSize: Int,
        val outputSize: Int,
        val precision: String,
        val numClasses: Int,
        val note: String
    )

    private var appContext: Context? = null
    private val models = mutableListOf<ImportedModel>()

    // ========== 生命周期 ==========

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        modelsDir().mkdirs()
        load()
    }

    fun modelsDir(): File = File(requireContext().filesDir, DIR_NAME)

    private fun registryFile(): File = File(modelsDir(), REGISTRY_NAME)

    private fun requireContext(): Context =
        appContext ?: error("ModelRepository.init() 未调用")

    // ========== 查询 ==========

    fun list(): List<ImportedModel> = models.toList()

    fun isEmpty(): Boolean = models.isEmpty()

    fun fileOf(model: ImportedModel): File = File(modelsDir(), model.fileName)

    fun absolutePathOf(model: ImportedModel): String = fileOf(model).absolutePath

    fun findByFileName(fileName: String): ImportedModel? =
        models.firstOrNull { it.fileName == fileName }

    // ========== 导入 ==========

    /**
     * 把 [uri] 指向的文件复制进 `models/` 并探测元数据。
     *
     * NCNN 的 `.bin` 权重文件不单独成为一个模型条目——它只是被复制到同目录下，
     * 等对应的 `.param` 导入时自然被 native 端按同名找到。调用方需要把 `.param`
     * 和 `.bin` 一起选中导入（顺序无所谓）。
     */
    fun importFrom(uri: Uri): Result<ImportedModel?> {
        val ctx = requireContext()
        return try {
            val originalName = queryDisplayName(uri) ?: "model_${System.currentTimeMillis()}"
            val safeName = sanitize(originalName)
            val ext = safeName.substringAfterLast('.', "").lowercase()

            if (ext !in SUPPORTED_EXT) {
                return Result.failure(
                    IllegalArgumentException("不支持的文件类型 .$ext（支持 .tflite / .param / .bin）")
                )
            }

            modelsDir().mkdirs()
            val target = uniqueTarget(safeName)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: return Result.failure(IllegalStateException("无法读取所选文件"))

            // .bin 只是 .param 的权重附属文件，不单独登记
            if (ext == "bin") {
                Log.d(TAG, "copied ncnn weights: ${target.name}")
                return Result.success(null)
            }

            val probe = probe(target)
            val model = ImportedModel(
                id = "m_${System.currentTimeMillis()}_${(0..0xFFFF).random().toString(16)}",
                fileName = target.name,
                displayName = target.nameWithoutExtension,
                precision = probe.precision,
                inputSize = probe.inputSize,
                outputSize = probe.outputSize,
                description = probe.note,
                classes = defaultClasses(probe.numClasses)
            )
            models.add(model)
            save()
            Log.i(TAG, "imported ${model.fileName}: $probe")
            Result.success(model)
        } catch (e: Exception) {
            Log.e(TAG, "import failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun update(model: ImportedModel) {
        val idx = models.indexOfFirst { it.id == model.id }
        if (idx >= 0) models[idx] = model else models.add(model)
        save()
    }

    /** 删除模型文件本身；`.param` 会连同同名 `.bin` 一并删掉。 */
    fun remove(model: ImportedModel) {
        try {
            fileOf(model).delete()
            if (model.fileName.endsWith(".param")) {
                File(modelsDir(), model.fileName.removeSuffix(".param") + ".bin").delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "delete failed: ${e.message}")
        }
        models.removeAll { it.id == model.id }
        save()
    }

    // ========== 元数据探测 ==========

    fun probe(file: File): Probe = when (file.extension.lowercase()) {
        "tflite" -> probeTflite(file)
        "param" -> probeNcnnParam(file)
        else -> Probe(0, 0, "UNKNOWN", 1, "未知格式")
    }

    /**
     * 用 TFLite Java Interpreter 读张量形状。
     *
     * 取值口径刻意和 native 端 `litert_engine.cpp` 的 `LiteRtEngine::init()`
     * 保持一致——输入取 shape[1]，输出的 num_outputs 取最后一维、channels 取
     * shape[1]，类别数 = channels - 4（cx/cy/w/h 之外的都是类别分数）。
     * 这样界面上显示的数字就是引擎实际会用的数字，对不上时便于排查。
     *
     * 唯一的偏离：shape[1] == 3 说明这是 NCHW 排布，此时 shape[1] 是通道数而非
     * 边长，改取 shape[2]。native 在这种情况下会取到 3，属于它的问题，这里不跟。
     */
    private fun probeTflite(file: File): Probe {
        var interpreter: Interpreter? = null
        return try {
            interpreter = Interpreter(file)

            val inTensor = interpreter.getInputTensor(0)
            val inShape = inTensor.shape()
            var inputSize = if (inShape.size >= 2) inShape[1] else 0
            if (inputSize == 3 && inShape.size >= 3) inputSize = inShape[2]   // NCHW

            val precision = when (inTensor.dataType()) {
                DataType.UINT8, DataType.INT8 -> "INT8"
                DataType.FLOAT32 -> "FLOAT32"
                else -> inTensor.dataType().name   // FLOAT16 等按枚举名原样显示
            }

            val outShape = interpreter.getOutputTensor(0).shape()
            val outputSize = if (outShape.isNotEmpty()) outShape[outShape.size - 1] else 0
            val channels = if (outShape.size > 1) outShape[1] else 0
            val numClasses = (channels - 4).coerceAtLeast(1)

            Probe(
                inputSize = inputSize,
                outputSize = outputSize,
                precision = precision,
                numClasses = numClasses,
                note = "TFLite  in${inShape.joinToString("x")}  out${outShape.joinToString("x")}"
            )
        } catch (e: Throwable) {
            // 探测失败不阻断导入——用户可以在对话框里手填
            Log.w(TAG, "probe tflite failed: ${e.message}")
            Probe(0, 0, "UNKNOWN", 1, "自动探测失败，请手动填写")
        } finally {
            try { interpreter?.close() } catch (_: Exception) {}
        }
    }

    /**
     * 解析 ncnn `.param` 纯文本，从 Input 层的 `0=W 1=H` 取输入尺寸。
     * ncnn 的 param 里没有类别数信息，类别只能由用户手填。
     */
    private fun probeNcnnParam(file: File): Probe {
        return try {
            var w = 0
            var h = 0
            file.useLines { lines ->
                for (line in lines) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.firstOrNull() != "Input") continue
                    for (p in parts) {
                        val kv = p.split('=')
                        if (kv.size != 2) continue
                        when (kv[0]) {
                            "0" -> w = kv[1].toIntOrNull() ?: 0
                            "1" -> h = kv[1].toIntOrNull() ?: 0
                        }
                    }
                    break
                }
            }
            val size = if (h > 0) h else w
            val binExists = File(modelsDir(), file.nameWithoutExtension + ".bin").exists()
            Probe(
                inputSize = size,
                outputSize = 0,
                precision = "UNKNOWN",
                numClasses = 1,
                note = if (binExists) "NCNN  ${w}x${h}" else "NCNN  ${w}x${h}  ⚠ 缺少同名 .bin"
            )
        } catch (e: Exception) {
            Log.w(TAG, "probe param failed: ${e.message}")
            Probe(0, 0, "UNKNOWN", 1, "自动探测失败，请手动填写")
        }
    }

    /** ncnn 模型缺了 `.bin` 就没法加载，导入后用这个提示用户补选。 */
    fun isMissingWeights(model: ImportedModel): Boolean =
        model.fileName.endsWith(".param") &&
            !File(modelsDir(), model.fileName.removeSuffix(".param") + ".bin").exists()

    private fun defaultClasses(n: Int): MutableMap<Int, String> =
        (0 until n.coerceAtLeast(1)).associateWith { "class_$it" }.toMutableMap()

    // ========== 持久化 ==========

    private fun load() {
        models.clear()
        val f = registryFile()
        if (!f.exists()) return
        try {
            val arr = JSONObject(f.readText()).optJSONArray("models") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val classes = mutableMapOf<Int, String>()
                o.optJSONObject("classes")?.let { co ->
                    co.keys().forEach { k -> k.toIntOrNull()?.let { classes[it] = co.getString(k) } }
                }
                val entry = ImportedModel(
                    id = o.optString("id", "m_$i"),
                    fileName = o.getString("fileName"),
                    displayName = o.optString("displayName", o.getString("fileName")),
                    precision = o.optString("precision", "UNKNOWN"),
                    inputSize = o.optInt("inputSize", 0),
                    outputSize = o.optInt("outputSize", 0),
                    description = o.optString("description", ""),
                    classes = if (classes.isEmpty()) defaultClasses(1) else classes
                )
                // 注册表里有、文件已经被用户从外面删掉的，直接跳过
                if (File(modelsDir(), entry.fileName).exists()) models.add(entry)
            }
        } catch (e: Exception) {
            Log.e(TAG, "load registry failed: ${e.message}", e)
        }
    }

    private fun save() {
        try {
            modelsDir().mkdirs()
            val arr = JSONArray()
            for (m in models) {
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("fileName", m.fileName)
                    put("displayName", m.displayName)
                    put("precision", m.precision)
                    put("inputSize", m.inputSize)
                    put("outputSize", m.outputSize)
                    put("description", m.description)
                    put("classes", JSONObject().apply {
                        m.classes.forEach { (k, v) -> put(k.toString(), v) }
                    })
                })
            }
            registryFile().writeText(JSONObject().put("models", arr).toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "save registry failed: ${e.message}", e)
        }
    }

    // ========== 工具 ==========

    private val SUPPORTED_EXT = setOf("tflite", "param", "bin")

    private fun queryDisplayName(uri: Uri): String? {
        val ctx = requireContext()
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._\\-]"), "_").take(120)

    /** 同名文件已存在时追加 `_1` / `_2`，避免覆盖用户之前导入的模型。 */
    private fun uniqueTarget(name: String): File {
        val dir = modelsDir()
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var n = 1
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(dir, "${base}_$n$suffix")
            n++
        }
        return candidate
    }
}
