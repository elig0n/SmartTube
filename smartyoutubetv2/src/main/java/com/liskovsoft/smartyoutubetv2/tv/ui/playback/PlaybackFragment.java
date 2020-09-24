package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.leanback.app.RowsSupportFragment;
import androidx.leanback.app.VideoSupportFragment;
import androidx.leanback.app.VideoSupportFragmentGlueHost;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ClassPresenterSelector;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.ext.leanback.LeanbackPlayerAdapter;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.util.Util;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controller.PlaybackController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controller.PlaybackEngineController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.autoframerate.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ExoPlayerController;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.PlayerController;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.RestoreTrackSelector;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.adapter.VideoGroupObjectAdapter;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.UriBackgroundManager;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plays selected video, loads playlist and related videos, and delegates playback to
 * {@link VideoPlayerGlue}.
 */
public class PlaybackFragment extends VideoSupportFragment implements PlaybackView, PlaybackController {
    private static final String TAG = PlaybackFragment.class.getSimpleName();
    private static final int UPDATE_DELAY = 16;
    private VideoPlayerGlue mPlayerGlue;
    private LeanbackPlayerAdapter mPlayerAdapter;
    private SimpleExoPlayer mPlayer;
    private DefaultTrackSelector mTrackSelector;
    private PlayerActionListener mPlaylistActionListener;
    private PlaybackPresenter mPlaybackPresenter;
    private ArrayObjectAdapter mRowsAdapter;
    private Map<Integer, VideoGroupObjectAdapter> mMediaGroupAdapters;
    private PlayerEventListener mEventListener;
    private PlayerController mExoPlayerController;
    private UriBackgroundManager mBackgroundManager;
    private final boolean mEnableAnimation = true;
    private RowsSupportFragment mRowsSupportFragment;
    private boolean mBlockEngine;
    private boolean mEnablePIP;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mMediaGroupAdapters = new HashMap<>();
        mBackgroundManager = ((LeanbackActivity) getActivity()).getBackgroundManager();
        mBackgroundManager.setBackgroundColor(ContextCompat.getColor(getActivity(), R.color.player_background));

        mPlaybackPresenter = PlaybackPresenter.instance(getContext());
        mPlaybackPresenter.register(this);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mPlaybackPresenter.onInitDone();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (Util.SDK_INT > 23) {
            initializePlayer();
            mEventListener.onViewResumed();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if ((Util.SDK_INT <= 23 || mPlayer == null)) {
            initializePlayer();
            mEventListener.onViewResumed();
        }
    }

    /** Pauses the player. */
    @TargetApi(Build.VERSION_CODES.N)
    @Override
    public void onPause() {
        super.onPause();

        if (Util.SDK_INT <= 23) {
            releasePlayer();
            mEventListener.onViewPaused();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (Util.SDK_INT > 23) {
            releasePlayer();
            mEventListener.onViewPaused();
        }
    }

    public void skipToNext() {
        mPlayerGlue.next();
    }

    public void skipToPrevious() {
        mPlayerGlue.previous();
    }

    public void rewind() {
        mPlayerGlue.rewind();
    }

    public void fastForward() {
        mPlayerGlue.fastForward();
    }

    private int getSuggestedRowIndex() {
        int selectedPosition = 0;

        if (mRowsSupportFragment != null) {
            selectedPosition = mRowsSupportFragment.getVerticalGridView().getSelectedPosition();
        }

        return selectedPosition;
    }

    public void restartPlayer() {
        if (mPlayer != null) {
            mEventListener.onEngineReleased();
        }
        destroyPlayerObjects();
        createPlayerObjects();
        mEventListener.onEngineInitialized();
    }

    private void releasePlayer() {
        if (isEngineBlocked()) {
            Log.d(TAG, "releasePlayer: Engine release is blocked. Exiting...");
            return;
        }

        if (mPlayer != null) {
            Log.d(TAG, "releasePlayer: Start releasing player engine...");
            mEventListener.onEngineReleased();
            destroyPlayerObjects();
        }
    }

    private void initializePlayer() {
        if (mPlayer != null) {
            Log.d(TAG, "Skip player initialization.");
            return;
        }

        createPlayerObjects();

        mEventListener.onEngineInitialized();
    }

    private void destroyPlayerObjects() {
        if (mPlayer != null) {
            mPlayer.release();
        }
        mPlayer = null;
        mTrackSelector = null;
        mPlayerGlue = null;
        mPlayerAdapter = null;
        mPlaylistActionListener = null;
        mExoPlayerController = null;
    }

    private void createPlayerObjects() {
        TrackSelection.Factory videoTrackSelectionFactory =
                new AdaptiveTrackSelection.Factory();
        mTrackSelector = new RestoreTrackSelector(videoTrackSelectionFactory);

        // Use default or pass your bandwidthMeter here: bandwidthMeter = new DefaultBandwidthMeter.Builder(getContext()).build()
        mPlayer = ExoPlayerFactory.newSimpleInstance(getActivity(), mTrackSelector);

        mExoPlayerController = new ExoPlayerController(mPlayer, mTrackSelector, getContext());
        mExoPlayerController.setEventListener(mEventListener);

        mPlayerAdapter = new LeanbackPlayerAdapter(getActivity(), mPlayer, UPDATE_DELAY);

        mPlaylistActionListener = new PlayerActionListener();
        mPlayerGlue = new VideoPlayerGlue(getActivity(), mPlayerAdapter, mPlaylistActionListener);
        mPlayerGlue.setHost(new VideoSupportFragmentGlueHost(this));
        mPlayerGlue.setSeekEnabled(true);
        mPlayerGlue.setControlsOverlayAutoHideEnabled(false); // don't show controls on some player events like play/pause/end
        hideControlsOverlay(mEnableAnimation); // hide controls upon fragment creation

        mRowsAdapter = initializeSuggestedVideosRow();
        setAdapter(mRowsAdapter);

        mRowsSupportFragment = (RowsSupportFragment) getChildFragmentManager().findFragmentById(
                R.id.playback_controls_dock);
    }

    private ArrayObjectAdapter initializeSuggestedVideosRow() {
        /*
         * To add a new row to the mPlayerAdapter and not lose the controls row that is provided by the
         * glue, we need to compose a new row with the controls row and our related videos row.
         *
         * We start by creating a new {@link ClassPresenterSelector}. Then add the controls row from
         * the media player glue, then add the related videos row.
         */
        ClassPresenterSelector presenterSelector = new ClassPresenterSelector();
        presenterSelector.addClassPresenter(
                mPlayerGlue.getControlsRow().getClass(), mPlayerGlue.getPlaybackRowPresenter());
        presenterSelector.addClassPresenter(ListRow.class, new ListRowPresenter());

        ArrayObjectAdapter rowsAdapter = new ArrayObjectAdapter(presenterSelector);

        // player controls row
        rowsAdapter.add(mPlayerGlue.getControlsRow());

        setOnItemViewClickedListener(new ItemViewClickedListener());

        return rowsAdapter;
    }

    private final class ItemViewClickedListener implements OnItemViewClickedListener {
        @Override
        public void onItemClicked(
                Presenter.ViewHolder itemViewHolder,
                Object item,
                RowPresenter.ViewHolder rowViewHolder,
                Row row) {

            if (item instanceof Video) {
                mEventListener.onSuggestionItemClicked((Video) item);
            }
        }
    }

    private class PlayerActionListener implements VideoPlayerGlue.OnActionClickedListener {
        @Override
        public void onPrevious() {
            mEventListener.onPreviousClicked();
        }

        @Override
        public void onNext() {
            mEventListener.onNextClicked();
        }

        @Override
        public void onPlay() {
            mEventListener.onPlayClicked();
        }

        @Override
        public void onPause() {
            mEventListener.onPauseClicked();
        }

        @Override
        public void onKeyDown(int keyCode) {
            mEventListener.onKeyDown(keyCode);
        }

        @Override
        public void setRepeatMode(int modeIndex) {
            mEventListener.onRepeatModeClicked(modeIndex);
        }

        @Override
        public void onHighQuality() {
            mEventListener.onHighQualityClicked();
        }

        @Override
        public void onSubscribe(boolean subscribed) {
            mEventListener.onSubscribeClicked(subscribed);
        }

        @Override
        public void onThumbsDown(boolean thumbsDown) {
            mEventListener.onThumbsDownClicked(thumbsDown);
        }

        @Override
        public void onThumbsUp(boolean thumbsUp) {
            mEventListener.onThumbsUpClicked(thumbsUp);
        }

        @Override
        public void onChannel() {
            mEventListener.onChannelClicked();
        }

        @Override
        public void onClosedCaptions() {
            mEventListener.onClosedCaptionsClicked();
        }

        @Override
        public void onPlaylistAdd() {
            mEventListener.onPlaylistAddClicked();
        }

        @Override
        public void onVideoStats() {
            mEventListener.onVideoStatsClicked();
        }
    }

    // Begin Ui events

    @Override
    public void resetSuggestedPosition() {
        if (mRowsSupportFragment != null && mRowsSupportFragment.getVerticalGridView() != null) {
            mRowsSupportFragment.getVerticalGridView().setSelectedPosition(0);
        }
    }

    @Override
    public void clearSuggestions() {
        if (mRowsAdapter.size() > 1) {
            mRowsAdapter.removeItems(1, mRowsAdapter.size() - 1);
        }

        mMediaGroupAdapters.clear();
    }

    @Override
    public void setVideo(Video video) {
        mExoPlayerController.setVideo(video);
        mPlayerGlue.setTitle(video.title);
        mPlayerGlue.setSubtitle(video.description);
    }

    // End Ui events

    // Begin Engine Events

    @Override
    public void openDash(InputStream dashManifest) {
        mExoPlayerController.openDash(dashManifest);
    }

    @Override
    public void openHls(String hlsPlaylistUrl) {
        mExoPlayerController.openHls(hlsPlaylistUrl);
    }

    @Override
    public long getPositionMs() {
        return mExoPlayerController.getPosition();
    }

    @Override
    public void setPositionMs(long positionMs) {
        mExoPlayerController.setPosition(positionMs);
    }

    @Override
    public long getLengthMs() {
        return mExoPlayerController.getLengthMs();
    }

    @Override
    public void setPlay(boolean play) {
        mExoPlayerController.setPlay(play);
    }

    @Override
    public boolean isPlaying() {
        return mExoPlayerController.isPlaying();
    }

    @Override
    public void setRepeatMode(int modeIndex) {
        mExoPlayerController.setRepeatMode(modeIndex);
    }

    @Override
    public List<FormatItem> getVideoFormats() {
        return mExoPlayerController.getVideoFormats();
    }

    @Override
    public List<FormatItem> getAudioFormats() {
        return mExoPlayerController.getAudioFormats();
    }

    @Override
    public void selectFormat(FormatItem option) {
        // Android 4.4 fix for format selection dialog (player destroyed when dialog is focused)
        mExoPlayerController.selectFormat(option);
    }

    @Override
    public FormatItem getVideoFormat() {
        return mExoPlayerController.getVideoFormat();
    }

    @Override
    public void blockEngine(boolean block) {
        mBlockEngine = block;
    }

    @Override
    public boolean isEngineBlocked() {
        return mBlockEngine;
    }

    @Override
    public void enablePIP(boolean enable) {
        mEnablePIP = enable;
    }

    @Override
    public boolean isPIPEnabled() {
        return mEnablePIP;
    }

    @Override
    public boolean isInPIPMode() {
        return ((PlaybackActivity) getActivity()).isInPIPMode();
    }

    // End Engine Events

    @Override
    public void setEventListener(PlayerEventListener listener) {
        mEventListener = listener;
    }

    @Override
    public PlaybackController getController() {
        return this;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mEventListener.onViewDestroyed();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Log.d(TAG, "Destroying PlaybackFragment...");

        // Fix situations when engine didn't properly destroyed.
        // E.g. after closing dialogs.
        blockEngine(false);
        releasePlayer();

        mPlaybackPresenter.unregister(this);
    }

    @Override
    public Video getVideo() {
        return mExoPlayerController.getVideo();
    }

    @Override
    public boolean isSuggestionsShown() {
        return isControlsOverlayVisible() && getSuggestedRowIndex() != 0;
    }

    @Override
    public void showControls(boolean show) {
        if (show) {
            showControlsOverlay(mEnableAnimation);
        } else {
            hideControlsOverlay(mEnableAnimation);
        }
    }

    @Override
    public void setRepeatButtonState(int modeIndex) {
        mPlayerGlue.setRepeatActionState(modeIndex);
    }

    @Override
    public void updateSuggestions(VideoGroup group) {
        if (mRowsAdapter == null) {
            Log.e(TAG, "Related videos row not initialized yet.");
            return;
        }

        HeaderItem rowHeader = new HeaderItem(group.getTitle());
        int mediaGroupId = group.getId(); // Create unique int from category.

        VideoGroupObjectAdapter existingAdapter = mMediaGroupAdapters.get(mediaGroupId);

        if (existingAdapter == null) {
            VideoGroupObjectAdapter mediaGroupAdapter = new VideoGroupObjectAdapter(group);

            mMediaGroupAdapters.put(mediaGroupId, mediaGroupAdapter);

            ListRow row = new ListRow(rowHeader, mediaGroupAdapter);
            mRowsAdapter.add(row);
        } else {
            existingAdapter.append(group); // continue row
        }
    }

    /* End PlayerController */
}
