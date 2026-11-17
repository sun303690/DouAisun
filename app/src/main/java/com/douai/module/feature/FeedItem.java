package com.douai.module.feature;

/** Feed attributes exposed by a version-specific adapter. */
public final class FeedItem {
    public final boolean advertisement;
    public final boolean live;
    public final boolean imagePost;
    public final boolean recommendationCard;
    public final String searchableText;
    public final long durationMillis;
    public final boolean durationKnown;
    public final long likeCount;
    public final boolean likeCountKnown;
    public final long commentCount;
    public final long collectCount;
    public final long shareCount;
    public final boolean engagementKnown;
    public final long createTimeSeconds;
    public final boolean createTimeKnown;

    public FeedItem(boolean advertisement, boolean live, boolean imagePost,
                    boolean recommendationCard, String searchableText,
                    long durationMillis, long likeCount) {
        this(advertisement, live, imagePost, recommendationCard, searchableText,
                durationMillis, likeCount, -1L, -1L, -1L, -1L);
    }

    public FeedItem(boolean advertisement, boolean live, boolean imagePost,
                    boolean recommendationCard, String searchableText,
                    long durationMillis, long likeCount,
                    long commentCount, long collectCount, long shareCount) {
        this(advertisement, live, imagePost, recommendationCard, searchableText,
                durationMillis, likeCount, commentCount, collectCount, shareCount, -1L);
    }

    public FeedItem(boolean advertisement, boolean live, boolean imagePost,
                    boolean recommendationCard, String searchableText,
                    long durationMillis, long likeCount,
                    long commentCount, long collectCount, long shareCount,
                    long createTimeSeconds) {
        this.advertisement = advertisement;
        this.live = live;
        this.imagePost = imagePost;
        this.recommendationCard = recommendationCard;
        this.searchableText = searchableText == null ? "" : searchableText;
        this.durationKnown = durationMillis >= 0;
        this.durationMillis = Math.max(0, durationMillis);
        this.likeCountKnown = likeCount >= 0;
        this.likeCount = Math.max(0, likeCount);
        this.commentCount = Math.max(0, commentCount);
        this.collectCount = Math.max(0, collectCount);
        this.shareCount = Math.max(0, shareCount);
        this.engagementKnown = commentCount >= 0 || collectCount >= 0 || shareCount >= 0;
        this.createTimeKnown = createTimeSeconds > 0;
        this.createTimeSeconds = Math.max(0, createTimeSeconds);
    }
}
