package me.kavin.piped.server.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.kavin.piped.consts.Constants;
import me.kavin.piped.utils.*;
import me.kavin.piped.utils.obj.*;
import me.kavin.piped.utils.obj.federation.FederatedVideoInfo;
import me.kavin.piped.utils.resp.InvalidRequestResponse;
import me.kavin.piped.utils.resp.VideoResolvedResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.utils.JsonUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static me.kavin.piped.consts.Constants.YOUTUBE_SERVICE;
import static me.kavin.piped.consts.Constants.mapper;
import static me.kavin.piped.utils.CollectionUtils.collectRelatedItems;
import static me.kavin.piped.utils.URLUtils.*;
import static org.schabi.newpipe.extractor.NewPipe.getPreferredContentCountry;
import static org.schabi.newpipe.extractor.NewPipe.getPreferredLocalization;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getJsonPostResponse;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareDesktopJsonBuilder;

public class StreamHandlers {
    public static byte[] streamsResponse(String videoId) throws Exception {

        Sentry.setExtra("videoId", videoId);

        final var futureStream = Multithreading.supplyAsync(() -> {
            Sentry.setExtra("videoId", videoId);
            ITransaction transaction = Sentry.startTransaction("StreamInfo fetch", "fetch");
            try {
                return StreamInfo.getInfo("https://www.youtube.com/watch?v=" + videoId);
            } catch (Exception e) {
                transaction.setThrowable(e);
                ExceptionUtils.rethrow(e);
            } finally {
                transaction.finish();
            }
            return null;
        });

        final var futureLbryId = Multithreading.supplyAsync(() -> {
            Sentry.setExtra("videoId", videoId);
            try {
                return LbryHelper.getLBRYId(videoId);
            } catch (Exception e) {
                ExceptionHandler.handle(e);
            }
            return null;
        });

        final var futureLBRY = Multithreading.supplyAsync(() -> {
            Sentry.setExtra("videoId", videoId);
            ITransaction transaction = Sentry.startTransaction("LBRY Stream fetch", "fetch");
            try {
                var childTask = transaction.startChild("fetch", "LBRY ID fetch");
                String lbryId = futureLbryId.get(2, TimeUnit.SECONDS);
                Sentry.setExtra("lbryId", lbryId);
                childTask.finish();

                return LbryHelper.getLBRYStreamURL(lbryId);
            } catch (TimeoutException ignored) {
            } catch (Exception e) {
                ExceptionHandler.handle(e);
            } finally {
                transaction.finish();
            }
            return null;
        });

        final var futureDislikeRating = Multithreading.supplyAsync(() -> {
            Sentry.setExtra("videoId", videoId);
            ITransaction transaction = Sentry.startTransaction("Dislike Rating", "fetch");
            try {
                return RydHelper.getDislikeRating(videoId);
            } catch (Exception e) {
                ExceptionHandler.handle(e);
            } finally {
                transaction.finish();
            }
            return null;
        });

        final List<Subtitle> subtitles = new ObjectArrayList<>();
        final List<ChapterSegment> chapters = new ObjectArrayList<>();

        final StreamInfo info = futureStream.get();

        info.getStreamSegments().forEach(segment -> chapters.add(new ChapterSegment(segment.getTitle(), rewriteURL(segment.getPreviewUrl()),
                segment.getStartTimeSeconds())));

        info.getSubtitles()
                .forEach(subtitle -> subtitles.add(new Subtitle(rewriteURL(subtitle.getContent()),
                        subtitle.getFormat().getMimeType(), subtitle.getDisplayLanguageName(),
                        subtitle.getLanguageTag(), subtitle.isAutoGenerated())));

        final List<PipedStream> videoStreams = new ObjectArrayList<>();
        final List<PipedStream> audioStreams = new ObjectArrayList<>();

        String lbryURL = null;

        try {
            lbryURL = futureLBRY.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            // ignored
        }

        if (lbryURL != null)
            videoStreams.add(new PipedStream(lbryURL, "MP4", "LBRY", "video/mp4", false));

        boolean livestream = info.getStreamType() == StreamType.LIVE_STREAM;

        if (!livestream) {
            info.getVideoOnlyStreams().forEach(stream -> videoStreams.add(new PipedStream(rewriteVideoURL(stream.getContent()),
                    String.valueOf(stream.getFormat()), stream.getResolution(), stream.getFormat().getMimeType(), true,
                    stream.getBitrate(), stream.getInitStart(), stream.getInitEnd(), stream.getIndexStart(),
                    stream.getIndexEnd(), stream.getCodec(), stream.getWidth(), stream.getHeight(), 30)));
            info.getVideoStreams()
                    .forEach(stream -> videoStreams
                            .add(new PipedStream(rewriteVideoURL(stream.getContent()), String.valueOf(stream.getFormat()),
                                    stream.getResolution(), stream.getFormat().getMimeType(), false)));

            info.getAudioStreams()
                    .forEach(stream -> audioStreams.add(new PipedStream(rewriteVideoURL(stream.getContent()),
                            String.valueOf(stream.getFormat()), stream.getAverageBitrate() + " kbps",
                            stream.getFormat().getMimeType(), false, stream.getBitrate(), stream.getInitStart(),
                            stream.getInitEnd(), stream.getIndexStart(), stream.getIndexEnd(), stream.getCodec())));
        }

        final List<ContentItem> relatedStreams = collectRelatedItems(info.getRelatedItems());

        long time = info.getUploadDate() != null ? info.getUploadDate().offsetDateTime().toInstant().toEpochMilli()
                : System.currentTimeMillis();

        if (info.getUploadDate() != null && System.currentTimeMillis() - time < TimeUnit.DAYS.toMillis(Constants.FEED_RETENTION)) {
            VideoHelpers.updateVideo(info.getId(), info, time);
            MatrixHelper.sendEvent("video.piped.stream.info", new FederatedVideoInfo(
                    info.getId(), StringUtils.substring(info.getUploaderUrl(), -24),
                    info.getName(),
                    info.getDuration(), info.getViewCount())
            );
        }

        String lbryId;

        try {
            lbryId = futureLbryId.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            lbryId = null;
        }

        // Attempt to get dislikes calculating with the RYD API rating
        if (info.getDislikeCount() < 0 && info.getLikeCount() >= 0) {
            double rating;
            try {
                rating = futureDislikeRating.get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                rating = -1;
            }

            if (rating > 1 && rating <= 5) {
                info.setDislikeCount(Math.round(info.getLikeCount() * ((5 - rating) / (rating - 1))));
            }
        }

        final Streams streams = new Streams(info.getName(), info.getDescription().getContent(),
                info.getTextualUploadDate(), info.getUploaderName(), substringYouTube(info.getUploaderUrl()),
                rewriteURL(info.getUploaderAvatarUrl()), rewriteURL(info.getThumbnailUrl()), info.getDuration(),
                info.getViewCount(), info.getLikeCount(), info.getDislikeCount(), info.getUploaderSubscriberCount(), info.isUploaderVerified(),
                audioStreams, videoStreams, relatedStreams, subtitles, livestream, rewriteVideoURL(info.getHlsUrl()),
                rewriteVideoURL(info.getDashMpdUrl()), lbryId, chapters);

        return mapper.writeValueAsBytes(streams);

    }

    public static byte[] resolveClipId(String clipId) throws Exception {

        final byte[] body = JsonWriter.string(prepareDesktopJsonBuilder(
                        getPreferredLocalization(), getPreferredContentCountry())
                        .value("url", "https://www.youtube.com/clip/" + clipId)
                        .done())
                .getBytes(UTF_8);

        final JsonObject jsonResponse = getJsonPostResponse("navigation/resolve_url",
                body, getPreferredLocalization());

        final String videoId = JsonUtils.getString(jsonResponse, "endpoint.watchEndpoint.videoId");

        return mapper.writeValueAsBytes(new VideoResolvedResponse(videoId));
    }

    public static byte[] commentsResponse(String videoId) throws Exception {

        Sentry.setExtra("videoId", videoId);

        CommentsInfo info = CommentsInfo.getInfo("https://www.youtube.com/watch?v=" + videoId);

        List<Comment> comments = new ObjectArrayList<>();

        info.getRelatedItems().forEach(comment -> {
            try {
                String repliespage = null;
                if (comment.getReplies() != null)
                    repliespage = mapper.writeValueAsString(comment.getReplies());

                comments.add(new Comment(comment.getUploaderName(), rewriteURL(comment.getUploaderAvatarUrl()),
                        comment.getCommentId(), comment.getCommentText(), comment.getTextualUploadDate(),
                        substringYouTube(comment.getUploaderUrl()), repliespage, comment.getLikeCount(), comment.getReplyCount(),
                        comment.isHeartedByUploader(), comment.isPinned(), comment.isUploaderVerified()));
            } catch (JsonProcessingException e) {
                ExceptionHandler.handle(e);
            }
        });

        String nextpage = null;
        if (info.hasNextPage()) {
            Page page = info.getNextPage();
            nextpage = mapper.writeValueAsString(page);
        }

        CommentsPage commentsItem = new CommentsPage(comments, nextpage, info.isCommentsDisabled());

        return mapper.writeValueAsBytes(commentsItem);

    }

    public static byte[] commentsPageResponse(String videoId, String prevpageStr) throws Exception {

        if (StringUtils.isEmpty(prevpageStr))
            ExceptionHandler.throwErrorResponse(new InvalidRequestResponse("nextpage is a required parameter"));

        Page prevpage = mapper.readValue(prevpageStr, Page.class);

        ListExtractor.InfoItemsPage<CommentsInfoItem> info = CommentsInfo.getMoreItems(YOUTUBE_SERVICE, "https://www.youtube.com/watch?v=" + videoId, prevpage);

        List<Comment> comments = new ObjectArrayList<>();

        info.getItems().forEach(comment -> {
            try {
                String repliespage = null;
                if (comment.getReplies() != null)
                    repliespage = mapper.writeValueAsString(comment.getReplies());

                comments.add(new Comment(comment.getUploaderName(), rewriteURL(comment.getUploaderAvatarUrl()),
                        comment.getCommentId(), comment.getCommentText(), comment.getTextualUploadDate(),
                        substringYouTube(comment.getUploaderUrl()), repliespage, comment.getLikeCount(), comment.getReplyCount(),
                        comment.isHeartedByUploader(), comment.isPinned(), comment.isUploaderVerified()));
            } catch (JsonProcessingException e) {
                ExceptionHandler.handle(e);
            }
        });

        String nextpage = null;
        if (info.hasNextPage()) {
            Page page = info.getNextPage();
            nextpage = mapper.writeValueAsString(page);
        }

        CommentsPage commentsItem = new CommentsPage(comments, nextpage, false);

        return mapper.writeValueAsBytes(commentsItem);

    }
}