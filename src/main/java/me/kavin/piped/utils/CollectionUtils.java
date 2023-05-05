package me.kavin.piped.utils;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.kavin.piped.utils.obj.*;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static me.kavin.piped.utils.URLUtils.*;

public class CollectionUtils {

    public static Streams collectStreamInfo(StreamInfo info) {
        final List<Subtitle> subtitles = new ObjectArrayList<>();
        final List<ChapterSegment> chapters = new ObjectArrayList<>();

        info.getStreamSegments().forEach(segment -> chapters.add(new ChapterSegment(segment.getTitle(), rewriteURL(segment.getPreviewUrl()),
                segment.getStartTimeSeconds())));

        final List<PreviewFrames> previewFrames = new ObjectArrayList<>();

        info.getPreviewFrames().forEach(frame -> previewFrames.add(new PreviewFrames(frame.getUrls().stream().map(URLUtils::rewriteURL).toList(), frame.getFrameWidth(),
                frame.getFrameHeight(), frame.getTotalCount(), frame.getDurationPerFrame(), frame.getFramesPerPageX(),
                frame.getFramesPerPageY())));

        info.getSubtitles()
                .forEach(subtitle -> subtitles.add(new Subtitle(rewriteURL(subtitle.getContent()),
                        subtitle.getFormat().getMimeType(), subtitle.getDisplayLanguageName(),
                        subtitle.getLanguageTag(), subtitle.isAutoGenerated())));

        final List<PipedStream> videoStreams = new ObjectArrayList<>();
        final List<PipedStream> audioStreams = new ObjectArrayList<>();

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
                            stream.getInitEnd(), stream.getIndexStart(), stream.getIndexEnd(), stream.getCodec(), stream.getAudioTrackId(), stream.getAudioTrackName(),
                            Optional.ofNullable(stream.getAudioTrackType()).map(Enum::name).orElse(null),
                            Optional.ofNullable(stream.getAudioLocale()).map(Locale::toLanguageTag).orElse(null)
                    )));
        }

        final List<ContentItem> relatedStreams = collectRelatedItems(info.getRelatedItems());

        return new Streams(info.getName(), info.getDescription().getContent(),
                info.getTextualUploadDate(), info.getUploaderName(), substringYouTube(info.getUploaderUrl()),
                rewriteURL(info.getUploaderAvatarUrl()), rewriteURL(info.getThumbnailUrl()), info.getDuration(),
                info.getViewCount(), info.getLikeCount(), info.getDislikeCount(), info.getUploaderSubscriberCount(), info.isUploaderVerified(),
                audioStreams, videoStreams, relatedStreams, subtitles, livestream, rewriteVideoURL(info.getHlsUrl()),
                rewriteVideoURL(info.getDashMpdUrl()), null, info.getCategory(), chapters, previewFrames);
    }

    public static List<ContentItem> collectRelatedItems(List<? extends InfoItem> items) {
        return items
                .stream()
                .parallel()
                .map(item -> {
                    if (item instanceof StreamInfoItem) {
                        return collectRelatedStream(item);
                    } else if (item instanceof PlaylistInfoItem) {
                        return collectRelatedPlaylist(item);
                    } else if (item instanceof ChannelInfoItem) {
                        return collectRelatedChannel(item);
                    } else {
                        throw new RuntimeException(
                                "Unknown item type: " + item.getClass().getName());
                    }
                }).toList();
    }

    private static StreamItem collectRelatedStream(Object o) {

        StreamInfoItem item = (StreamInfoItem) o;

        return new StreamItem(substringYouTube(item.getUrl()), item.getName(),
                rewriteURL(item.getThumbnailUrl()),
                item.getUploaderName(), substringYouTube(item.getUploaderUrl()),
                rewriteURL(item.getUploaderAvatarUrl()), item.getTextualUploadDate(),
                item.getShortDescription(), item.getDuration(),
                item.getViewCount(), item.getUploadDate() != null ?
                item.getUploadDate().offsetDateTime().toInstant().toEpochMilli() : -1,
                item.isUploaderVerified(), item.isShortFormContent());
    }

    private static PlaylistItem collectRelatedPlaylist(Object o) {

        PlaylistInfoItem item = (PlaylistInfoItem) o;

        return new PlaylistItem(substringYouTube(item.getUrl()), item.getName(),
                rewriteURL(item.getThumbnailUrl()),
                item.getUploaderName(), substringYouTube(item.getUploaderUrl()),
                item.isUploaderVerified(),
                item.getPlaylistType().name(), item.getStreamCount());
    }

    private static ChannelItem collectRelatedChannel(Object o) {

        ChannelInfoItem item = (ChannelInfoItem) o;

        return new ChannelItem(substringYouTube(item.getUrl()), item.getName(),
                rewriteURL(item.getThumbnailUrl()),
                item.getDescription(), item.getSubscriberCount(), item.getStreamCount(),
                item.isVerified());
    }
}
