package team.maodie.aimbot

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AppConfig(
    var aimbotEnabled: Boolean = false,
    var speed: Float = 0.15f,
    var ki: Float = 0.02f,
    var kd: Float = 0.08f,
    var aimOffsetYRatio: Float = 0f,
    var aimSwayAmplitude: Int = 0,
    var aimPrediction: Int = 0,
    var triggerOffsetYRatio: Float = 0f,
    var aimHoldEnabled: Boolean = false,
    var aimTouchDisplay: Boolean = false,
    var aimTouchSize: Int = 20,
    var confidence: Float = 0.50f,
    var modelIndex: Int = 0,
    var triggerEnabled: Boolean = false,
    var triggerReactionSpeed: Int = 100,
    var triggerCooldown: Int = 200,
    var triggerUpFluctuation: Int = 3,
    var triggerDownFluctuation: Int = 3,
    var triggerTouchDuration: Int = 10,
    var triggerTouchRange: Int = 100,
    var triggerShowArea: Boolean = false,
    var range: Int = 300,
    var showCaptureRange: Boolean = false,
    var showDetectionBox: Boolean = false,
    var showCenterDot: Boolean = false,
    var areaSettingsEnabled: Boolean = false,
    var areas: List<AreaConfig> = emptyList(),
    var aimMode: Int = 0,
    var bezierDuration: Int = 30,
    var bezierControlOffset: Float = 0.3f,
    var bezierRandomSpread: Float = 0.1f,
    var aimClasses: Set<Int> = emptySet(),  // empty = all classes
    var priorityClass: Int = -1,            // -1 = no priority
    var classAimOffsets: Map<Int, Float> = emptyMap(),  // per-class Y offset overrides
    var boxAimRatio: Float = 0.5f,          // 0=top, 0.5=center, 1=bottom
    var classBoxAimRatios: Map<Int, Float> = emptyMap(),  // per-class box aim ratio
    var classTriggerOffsets: Map<Int, Float> = emptyMap(),  // per-class trigger Y offset
    var triggerClasses: Set<Int> = emptySet()  // empty = all classes
)

object ConfigManager {
    private const val CONFIG_FILE = "config.json"
    private var config: AppConfig = AppConfig()
    private var configFile: File? = null

    fun init(context: Context) {
        configFile = File(context.filesDir, CONFIG_FILE)
        load()
    }

    fun load(): AppConfig {
        try {
            configFile?.let { file ->
                if (file.exists()) {
                    val json = file.readText()
                    val obj = JSONObject(json)
                    config = AppConfig(
                        aimbotEnabled = obj.optBoolean("aimbotEnabled", false),
                        speed = obj.optDouble("speed", 0.3).toFloat(),
                        ki = obj.optDouble("ki", 0.02).toFloat(),
                        kd = obj.optDouble("kd", 0.08).toFloat(),
                        aimOffsetYRatio = obj.optDouble("aimOffsetYRatio", 0.0).toFloat(),
                        aimSwayAmplitude = obj.optInt("aimSwayAmplitude", 0),
                        aimPrediction = obj.optInt("aimPrediction", 0),
                        triggerOffsetYRatio = obj.optDouble("triggerOffsetYRatio", 0.0).toFloat(),
                        aimHoldEnabled = obj.optBoolean("aimHoldEnabled", false),
                        aimTouchDisplay = obj.optBoolean("aimTouchDisplay", false),
                        aimTouchSize = obj.optInt("aimTouchSize", 20),
                        confidence = obj.optDouble("confidence", 0.50).toFloat(),
                        modelIndex = obj.optInt("modelIndex", 0),
                        triggerEnabled = obj.optBoolean("triggerEnabled", false),
                        triggerReactionSpeed = obj.optInt("triggerReactionSpeed", 100),
                        triggerCooldown = obj.optInt("triggerCooldown", 200),
                        triggerUpFluctuation = obj.optInt("triggerUpFluctuation", 3),
                        triggerDownFluctuation = obj.optInt("triggerDownFluctuation", 3),
                        triggerTouchDuration = obj.optInt("triggerTouchDuration", 10),
                        triggerTouchRange = obj.optInt("triggerTouchRange", 100),
                        triggerShowArea = obj.optBoolean("triggerShowArea", false),
                        range = obj.optInt("range", 300),
                        showCaptureRange = obj.optBoolean("showCaptureRange", false),
                        showDetectionBox = obj.optBoolean("showDetectionBox", false),
                        showCenterDot = obj.optBoolean("showCenterDot", false),
                        areaSettingsEnabled = obj.optBoolean("areaSettingsEnabled", false),
                        areas = parseAreas(obj.optJSONArray("areas")),
                        aimMode = obj.optInt("aimMode", 0),
                        bezierDuration = obj.optInt("bezierDuration", 30),
                        bezierControlOffset = obj.optDouble("bezierControlOffset", 0.3).toFloat(),
                        bezierRandomSpread = obj.optDouble("bezierRandomSpread", 0.1).toFloat(),
                        aimClasses = parseIntSet(obj.optJSONArray("aimClasses")),
                        priorityClass = obj.optInt("priorityClass", -1),
                        classAimOffsets = parseFloatMap(obj.optJSONObject("classAimOffsets")),
                        boxAimRatio = obj.optDouble("boxAimRatio", 0.5).toFloat(),
                        classBoxAimRatios = parseFloatMap(obj.optJSONObject("classBoxAimRatios")),
                        classTriggerOffsets = parseFloatMap(obj.optJSONObject("classTriggerOffsets")),
                        triggerClasses = parseIntSet(obj.optJSONArray("triggerClasses"))
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return config
    }

    fun save() {
        try {
            configFile?.let { file ->
                val obj = JSONObject().apply {
                    put("aimbotEnabled", config.aimbotEnabled)
                    put("speed", config.speed.toDouble())
                    put("ki", config.ki.toDouble())
                    put("kd", config.kd.toDouble())
                    put("aimOffsetYRatio", config.aimOffsetYRatio.toDouble())
                    put("aimSwayAmplitude", config.aimSwayAmplitude)
                    put("aimPrediction", config.aimPrediction)
                    put("triggerOffsetYRatio", config.triggerOffsetYRatio.toDouble())
                    put("aimHoldEnabled", config.aimHoldEnabled)
                    put("aimTouchDisplay", config.aimTouchDisplay)
                    put("aimTouchSize", config.aimTouchSize)
                    put("confidence", config.confidence.toDouble())
                    put("modelIndex", config.modelIndex)
                    put("triggerEnabled", config.triggerEnabled)
                    put("triggerReactionSpeed", config.triggerReactionSpeed)
                    put("triggerCooldown", config.triggerCooldown)
                    put("triggerUpFluctuation", config.triggerUpFluctuation)
                    put("triggerDownFluctuation", config.triggerDownFluctuation)
                    put("triggerTouchDuration", config.triggerTouchDuration)
                    put("triggerTouchRange", config.triggerTouchRange)
                    put("triggerShowArea", config.triggerShowArea)
                    put("range", config.range)
                    put("showCaptureRange", config.showCaptureRange)
                    put("showDetectionBox", config.showDetectionBox)
                    put("showCenterDot", config.showCenterDot)
                    put("areaSettingsEnabled", config.areaSettingsEnabled)
                    put("areas", serializeAreas(config.areas))
                    put("aimMode", config.aimMode)
                    put("bezierDuration", config.bezierDuration)
                    put("bezierControlOffset", config.bezierControlOffset.toDouble())
                    put("bezierRandomSpread", config.bezierRandomSpread.toDouble())
                    put("aimClasses", serializeIntSet(config.aimClasses))
                    put("priorityClass", config.priorityClass)
                    put("classAimOffsets", serializeFloatMap(config.classAimOffsets))
                    put("boxAimRatio", config.boxAimRatio.toDouble())
                    put("classBoxAimRatios", serializeFloatMap(config.classBoxAimRatios))
                    put("classTriggerOffsets", serializeFloatMap(config.classTriggerOffsets))
                    put("triggerClasses", serializeIntSet(config.triggerClasses))
                }
                file.writeText(obj.toString(2))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getConfig(): AppConfig = config

    private fun parseAreas(arr: JSONArray?): List<AreaConfig> {
        if (arr == null) return emptyList()
        val list = mutableListOf<AreaConfig>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(AreaConfig(
                x = o.optInt("x", 0),
                y = o.optInt("y", 0),
                width = o.optInt("width", 150),
                height = o.optInt("height", 150),
                name = o.optString("name", ""),
                color = o.optInt("color", Color.RED)
            ))
        }
        return list
    }

    private fun parseIntSet(arr: JSONArray?): Set<Int> {
        if (arr == null) return emptySet()
        val set = mutableSetOf<Int>()
        for (i in 0 until arr.length()) set.add(arr.getInt(i))
        return set
    }

    private fun serializeIntSet(set: Set<Int>): JSONArray {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        return arr
    }

    private fun parseFloatMap(obj: JSONObject?): Map<Int, Float> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<Int, Float>()
        obj.keys().forEach { key -> map[key.toInt()] = obj.optDouble(key, 0.0).toFloat() }
        return map
    }

    private fun serializeFloatMap(map: Map<Int, Float>): JSONObject {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k.toString(), v.toDouble()) }
        return obj
    }

    private fun serializeAreas(areas: List<AreaConfig>): JSONArray {
        val arr = JSONArray()
        areas.forEach { a ->
            arr.put(JSONObject().apply {
                put("x", a.x)
                put("y", a.y)
                put("width", a.width)
                put("height", a.height)
                put("name", a.name)
                put("color", a.color)
            })
        }
        return arr
    }

    fun updateConfig(block: AppConfig.() -> Unit) {
        config.block()
        save()
    }

    fun exportToUri(context: Context, uri: android.net.Uri) {
        try {
            val file = File(context.filesDir, CONFIG_FILE)
            if (!file.exists()) return
            val json = file.readText()
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(json.toByteArray())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun importFromUri(context: Context, uri: android.net.Uri) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val json = input.bufferedReader().readText()
                val file = File(context.filesDir, CONFIG_FILE)
                file.writeText(json)
                load()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}