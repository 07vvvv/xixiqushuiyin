package com.aiqu.watermark.ui.douyin

/**
 * 抖音抓取结果的中转站
 *
 * 详情 JSON 可能很大（图集作品可达上百 KB），不适合塞进 Intent，
 * 因此用单例传递，调用方在 onActivityResult 里读取。
 */
object DouyinCaptureHolder {

    @Volatile
    var lastAwemeJson: String? = null
        private set

    /** 说明本次结果是「详情接口」还是「页面视频元素」兜底取得，便于界面提示 */
    @Volatile
    var lastSource: String = ""
        private set

    fun set(json: String, source: String) {
        lastAwemeJson = json
        lastSource = source
    }

    fun clear() {
        lastAwemeJson = null
        lastSource = ""
    }
}
