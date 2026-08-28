package com.videoagent.summary.provider;

public interface VideoSummaryProvider {

    VideoSummaryDraft summarize(VideoSummaryRequest request);
}
