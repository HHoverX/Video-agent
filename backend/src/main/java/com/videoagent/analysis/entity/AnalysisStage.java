package com.videoagent.analysis.entity;

public enum AnalysisStage {
    QUEUED("任务已进入队列"),
    PREPARING("正在准备分析"),
    EXTRACTING_AUDIO("正在提取音频"),
    TRANSCRIBING("正在生成带时间戳字幕"),
    TRANSCRIPT_SAVED("时间戳字幕已保存"),
    SAVING_TRANSCRIPT("正在保存时间戳字幕"),
    SUMMARIZING("正在生成结构化视频总结"),
    SUMMARY_SAVED("结构化总结已保存"),
    ANALYZING("正在模拟分析"),
    PROCESSING("正在处理分析结果"),
    SAVING("正在保存结果"),
    DONE("分析完成"),
    FAILED("分析失败"),
    RETRY_WAITING("分析暂时失败，正在重试");

    private final String message;

    AnalysisStage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    public static String messageFor(String stage) {
        if (stage == null) {
            return "任务状态未知";
        }
        try {
            return valueOf(stage).message();
        } catch (IllegalArgumentException exception) {
            return stage;
        }
    }
}
