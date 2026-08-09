package com.videoagent.summary.provider;

public interface VideoSummaryProvider {

    VideoSummaryResult summarize(VideoSummaryRequest request);
}
