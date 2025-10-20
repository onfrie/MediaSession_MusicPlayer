package cn.yinxm.media.ms.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.os.Bundle;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.View;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import android.os.SystemClock;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "Client_MediaBrowser";

    Context mContext;
    PackageManager mPackageManager;
    TextView mTvInfo;

    // 新增UI组件
    private TextView tvSongTitle;
    private TextView tvArtist;
    private TextView tvCurrentTime;
    private TextView tvTotalTime;
    private TextView tvStatus;
    private ImageView ivAlbum;
    private SeekBar seekBar;

    // 控制按钮
    private ImageButton btnPlayPause;
    private ImageButton btnPrevious;
    private ImageButton btnNext;

    MediaBrowserCompat mMediaBrowser;
    private MediaControllerCompat mController;
    PlayInfo mPlayInfo = new PlayInfo();

    // 进度更新相关
    private Timer progressUpdateTimer;
    private boolean isUserSeeking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mContext = getApplicationContext();
        mPackageManager = getPackageManager();

        // 初始化UI组件
        initViews();
    }

    private void initViews() {
        mTvInfo = findViewById(R.id.tv_info);
        tvSongTitle = findViewById(R.id.tv_song_title);
        tvArtist = findViewById(R.id.tv_artist);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvTotalTime = findViewById(R.id.tv_total_time);
        tvStatus = findViewById(R.id.tv_status);
        ivAlbum = findViewById(R.id.iv_album);
        seekBar = findViewById(R.id.seek_bar);

        // 初始化控制按钮
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnPrevious = findViewById(R.id.btn_previous);
        btnNext = findViewById(R.id.btn_next);

        // 设置控制按钮点击事件
        btnPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handlePlayPause();
            }
        });

        btnPrevious.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handlePrevious();
            }
        });

        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleNext();
            }
        });

        // 设置进度条监听
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mController != null) {
                    // 用户拖拽时更新时间显示
                    long duration = mController.getMetadata() != null ?
                            mController.getMetadata().getLong(MediaMetadataCompat.METADATA_KEY_DURATION) : 0;
                    if (duration > 0) {
                        long position = (long) (duration * progress / 1000.0f);
                        tvCurrentTime.setText(formatTime(position / 1000));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isUserSeeking = false;
                // 用户拖拽进度条时，发送跳转指令
                if (mController != null) {
                    long duration = mController.getMetadata() != null ?
                            mController.getMetadata().getLong(MediaMetadataCompat.METADATA_KEY_DURATION) : 0;
                    if (duration > 0) {
                        long position = (long) (duration * seekBar.getProgress() / 1000.0f);
                        mController.getTransportControls().seekTo(position);
                    }
                }
            }
        });
    }


    @Override
    protected void onResume() {
        super.onResume();
        connectRemoteService();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopProgressTimer();
    }

    
    private void connectRemoteService() {
        // TODO: 2019-07-31 车载蓝牙音乐 实际上是通过获取所有的MediaBrowserService结合当前的MediaSession以及音频焦点状态，
        //  来知道该显示哪个多媒体app的播放数据
//        Intent intent = new Intent(MediaBrowserService.SERVICE_INTERFACE);
//        List<ResolveInfo> resInfos = mPackageManager.queryIntentServices(intent, PackageManager.MATCH_ALL);
//        Log.d(TAG, "resInfos=" + resInfos);
//        for (ResolveInfo resolveInfo : resInfos) {
//            Log.d(TAG, "pkg=" + resolveInfo.serviceInfo.packageName + ", service=" + resolveInfo.serviceInfo.name + ", " + resolveInfo.loadLabel(mPackageManager).toString());
//        }
//        if (resInfos.isEmpty()) {
//            return;
//        }

        // 1.待连接的服务
        ComponentName componentName = new ComponentName("cn.yinxm.media.ms", "cn.yinxm.media.ms.MusicService");
//        ComponentName componentName = new ComponentName(resInfos.get(0).serviceInfo.packageName,
//                resInfos.get(0).serviceInfo.name);
        // 2.创建MediaBrowser
        mMediaBrowser = new MediaBrowserCompat(mContext, componentName, mConnectionCallbacks, null);
        // 3.建立连接
        mMediaBrowser.connect();
    }

    private void refreshPlayInfo() {
        mTvInfo.setText(mPlayInfo.debugInfo());
    }

    private void updatePlayState(PlaybackStateCompat state) {
        if (state == null) {
            return;
        }
        mPlayInfo.setState(state);
        refreshPlayInfo();
    }

    private void updatePlayMetadata(MediaMetadataCompat metadata) {
        if (metadata == null) {
            return;
        }
        mPlayInfo.setMetadata(metadata);
        refreshPlayInfo();
    }


    private final MediaBrowserCompat.ConnectionCallback mConnectionCallbacks =
            new MediaBrowserCompat.ConnectionCallback() {

                @Override
                public void onConnected() {
                    Log.d(TAG, "MediaBrowser.onConnected - 连接成功！");
                    if (mMediaBrowser.isConnected()) {
                        // 更新状态显示
                        tvStatus.setText("已连接");
                        tvStatus.setTextColor(0xFF27AE60);

                        String mediaId = mMediaBrowser.getRoot();
                        Log.d(TAG, "获取根mediaId: " + mediaId);
                        mMediaBrowser.unsubscribe(mediaId);
                        //之前说到订阅的方法还需要一个参数，即设置订阅回调SubscriptionCallback
                        //当Service获取数据后会将数据发送回来，此时会触发SubscriptionCallback.onChildrenLoaded回调
                        mMediaBrowser.subscribe(mediaId, BrowserSubscriptionCallback);
                        try {
                            mController = new MediaControllerCompat(MainActivity.this, mMediaBrowser.getSessionToken());
                            mController.registerCallback(mMediaControllerCallback);

                            Log.d(TAG, "MediaController创建成功，当前metadata: " +
                                  (mController.getMetadata() != null ? "有数据" : "无数据"));
                            Log.d(TAG, "当前playbackState: " +
                                  (mController.getPlaybackState() != null ?
                                   mController.getPlaybackState().getState() : "无状态"));

                            // 立即更新UI显示
                            if (mController.getMetadata() != null) {
                                updatePlayMetadata(mController.getMetadata());
                                updatePlayerUI(mController.getMetadata());
                            }
                            if (mController.getPlaybackState() != null) {
                                updatePlayState(mController.getPlaybackState());
                                updatePlayerState(mController.getPlaybackState());
                            }
                        } catch (RemoteException e) {
                            Log.e(TAG, "创建MediaController失败", e);
                            e.printStackTrace();
                        }
                    }
                }

                @Override
                public void onConnectionSuspended() {
                    // 连接中断回调
                    Log.d(TAG, "onConnectionSuspended");
                }

                @Override
                public void onConnectionFailed() {
                    Log.e(TAG, "onConnectionFailed - 连接失败！");
                    tvStatus.setText("连接失败");
                    tvStatus.setTextColor(0xFFE74C3C);
                }
            };

    /**
     * 向媒体浏览器服务(MediaBrowserService)发起数据订阅请求的回调接口
     */
    private final MediaBrowserCompat.SubscriptionCallback BrowserSubscriptionCallback =
            new MediaBrowserCompat.SubscriptionCallback() {
                @Override
                public void onChildrenLoaded(@NonNull String parentId,
                                             @NonNull List<MediaBrowserCompat.MediaItem> children) {
                    Log.e(TAG, "onChildrenLoaded------" + children);
                    mPlayInfo.setChildren(children);
                    refreshPlayInfo();
                }
            };


    /**
     * 被动接收MediaSession播放信息、状态改变
     */
    MediaControllerCompat.Callback mMediaControllerCallback =
            new MediaControllerCompat.Callback() {
                @Override
                public void onSessionDestroyed() {
                    // Session销毁
                    Log.d(TAG, "onSessionDestroyed");
                }

                @Override
                public void onRepeatModeChanged(int repeatMode) {
                    // 循环模式发生变化
                    Log.d(TAG, "onRepeatModeChanged");
                }

                @Override
                public void onShuffleModeChanged(int shuffleMode) {
                    // 随机模式发生变化
                    Log.d(TAG, "onShuffleModeChanged");
                }

                @Override
                public void onQueueChanged(List<MediaSessionCompat.QueueItem> queue) {
                    // 播放列表更新回调
                }

                @Override
                public void onMetadataChanged(MediaMetadataCompat metadata) {
                    // 歌曲元数据变化
                    Log.e(TAG, "onMetadataChanged: " + metadata.getDescription().getTitle());
                    updatePlayMetadata(metadata);
                    updatePlayerUI(metadata);
                }

                @Override
                public void onPlaybackStateChanged(PlaybackStateCompat state) {
                    // 播放状态变化（包含进度信息）
                    Log.d(TAG, "onPlaybackStateChanged: " + state.getState() +
                          ", position: " + formatTime(state.getPosition() / 1000));
                    updatePlayState(state);
                    updatePlayerState(state);

                    // 实时刷新显示
                    refreshPlayInfo();
                }
            };

    // 格式化时间方法
    private String formatTime(long seconds) {
        long minutes = seconds / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d", minutes, secs);
    }

    /**
     * 处理播放/暂停按钮点击
     */
    private void handlePlayPause() {
        if (mController != null) {
            PlaybackStateCompat state = mController.getPlaybackState();
            if (state != null) {
                if (state.getState() == PlaybackStateCompat.STATE_PLAYING) {
                    // 当前正在播放，发送暂停指令
                    Log.d(TAG, "发送暂停指令");
                    mController.getTransportControls().pause();
                } else {
                    // 当前未播放，发送播放指令
                    Log.d(TAG, "发送播放指令");
                    mController.getTransportControls().play();
                }
            } else {
                // 没有播放状态，发送播放指令
                Log.d(TAG, "没有播放状态，发送播放指令");
                mController.getTransportControls().play();
            }
        } else {
            Log.w(TAG, "MediaController为null，无法控制播放");
        }
    }

    /**
     * 处理上一首按钮点击
     */
    private void handlePrevious() {
        if (mController != null) {
            Log.d(TAG, "发送上一首指令");
            mController.getTransportControls().skipToPrevious();
        } else {
            Log.w(TAG, "MediaController为null，无法切换上一首");
        }
    }

    /**
     * 处理下一首按钮点击
     */
    private void handleNext() {
        if (mController != null) {
            Log.d(TAG, "发送下一首指令");
            mController.getTransportControls().skipToNext();
        } else {
            Log.w(TAG, "MediaController为null，无法切换下一首");
        }
    }

    // 静态格式化时间方法，供内部类使用
    public static String formatTimeStatic(long seconds) {
        long minutes = seconds / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d", minutes, secs);
    }

    /**
     * 更新播放器UI（歌曲信息）
     */
    private void updatePlayerUI(MediaMetadataCompat metadata) {
        if (metadata != null) {
            CharSequence title = metadata.getDescription().getTitle();
            CharSequence artist = metadata.getDescription().getSubtitle();

            tvSongTitle.setText(title != null ? title : "未知歌曲");
            tvArtist.setText(artist != null ? artist : "未知歌手");

            // 更新总时长
            long duration = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
            tvTotalTime.setText(formatTime(duration / 1000));

            // 设置进度条最大值
            seekBar.setMax(1000);

            Log.d(TAG, "MusicClient更新UI: " + title + " - " + artist +
                  ", 时长: " + formatTime(duration / 1000) +
                  " (" + duration + "ms)");
        } else {
            Log.w(TAG, "MusicClient: metadata为null，无法更新UI");
        }
    }

    /**
     * 更新播放器状态
     */
    private void updatePlayerState(PlaybackStateCompat state) {
        if (state != null) {
            boolean isPlaying = state.getState() == PlaybackStateCompat.STATE_PLAYING;

            // 更新状态文本
            if (isPlaying) {
                tvStatus.setText("播放中");
                tvStatus.setTextColor(0xFF27AE60);
                startProgressTimer();
            } else {
                tvStatus.setText("已暂停");
                tvStatus.setTextColor(0xFFE74C3C);
                stopProgressTimer();
                // 暂停时也要更新一次进度，显示准确的暂停位置
                updateProgressFromState(state);
            }

            // 更新播放按钮图标
            updatePlayPauseButton(isPlaying);

            // 立即更新一次进度
            updateProgressFromState(state);
        }
    }

    /**
     * 更新播放/暂停按钮图标
     */
    private void updatePlayPauseButton(boolean isPlaying) {
        if (isPlaying) {
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            Log.d(TAG, "更新按钮图标为暂停");
        } else {
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
            Log.d(TAG, "更新按钮图标为播放");
        }
    }

    /**
     * 开始进度更新定时器
     */
    private void startProgressTimer() {
        stopProgressTimer();
        progressUpdateTimer = new Timer();
        progressUpdateTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateProgressFromController();
                    }
                });
            }
        }, 0, 1000); // 每秒更新一次
    }

    /**
     * 停止进度更新定时器
     */
    private void stopProgressTimer() {
        if (progressUpdateTimer != null) {
            progressUpdateTimer.cancel();
            progressUpdateTimer = null;
        }
    }

    /**
     * 从MediaController更新进度
     */
    private void updateProgressFromController() {
        if (mController != null && !isUserSeeking) {
            PlaybackStateCompat state = mController.getPlaybackState();
            updateProgressFromState(state);
        }
    }

    /**
     * 从PlaybackState更新进度条
     */
    private void updateProgressFromState(PlaybackStateCompat state) {
        if (state != null && !isUserSeeking && mController != null) {
            long position = state.getPosition();
            if (state.getState() == PlaybackStateCompat.STATE_PLAYING) {
                // 如果正在播放，需要根据时间差计算当前实际位置
                long updateTime = state.getLastPositionUpdateTime();
                long currentTime = SystemClock.elapsedRealtime();
                position += (currentTime - updateTime);
            }

            long duration = mController.getMetadata() != null ?
                    mController.getMetadata().getLong(MediaMetadataCompat.METADATA_KEY_DURATION) : 0;

            if (duration > 0) {
                // 确保位置不超过总时长
                position = Math.min(position, duration);
                int progress = (int) (position * 1000 / duration);

                seekBar.setProgress(progress);
                tvCurrentTime.setText(formatTime(position / 1000));

                Log.d(TAG, "MusicClient更新进度: " + formatTime(position / 1000) +
                      " / " + formatTime(duration / 1000) +
                      " (进度: " + progress + "/1000)");
            } else {
                Log.w(TAG, "MusicClient: 音乐时长为0，无法更新进度条");
                tvCurrentTime.setText("00:00");
                tvTotalTime.setText("00:00");
            }
        }
    }

    static class PlayInfo {
        private MediaMetadataCompat metadata;
        private PlaybackStateCompat state;
        private List<MediaBrowserCompat.MediaItem> children;


        public void setMetadata(MediaMetadataCompat metadata) {
            this.metadata = metadata;
        }

        public void setState(PlaybackStateCompat state) {
            this.state = state;
        }

        public void setChildren(List<MediaBrowserCompat.MediaItem> children) {
            this.children = children;
        }

        public String debugInfo() {
            StringBuilder builder = new StringBuilder();

            if (state != null) {
                builder.append("=== MediaSession播放状态 ===\n");
                builder.append("当前播放状态：\t" + (state.getState() == PlaybackStateCompat.STATE_PLAYING ? "播放中" : "未播放"));
                if (state.getState() == PlaybackStateCompat.STATE_PLAYING) {
                    builder.append(" (位置: ").append(formatTime(state.getPosition() / 1000)).append(")");
                }
                builder.append("\n\n");
            }

            if (metadata != null) {
                builder.append("=== MediaSession元数据 ===\n");
                builder.append("当前播放信息：\t" + transform(metadata));

                // 添加进度条可视化
                if (state != null && state.getState() == PlaybackStateCompat.STATE_PLAYING) {
                    long duration = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
                    long position = state.getPosition();

                    builder.append("\n");
                    builder.append("播放进度：").append(MainActivity.formatTimeStatic(position / 1000))
                           .append(" / ").append(MainActivity.formatTimeStatic(duration / 1000)).append("\n");
                    builder.append("进度条：").append(createProgressBar(position, (int)duration)).append("\n");
                }
                builder.append("\n\n");
            }

            if (children != null && !children.isEmpty()) {
                builder.append("=== 播放列表 ===\n");
                for (int i = 0; i < children.size(); i++) {
                    MediaBrowserCompat.MediaItem mediaItem = children.get(i);
                    builder.append((i + 1) + " " + mediaItem.getDescription().getTitle() + " - " + mediaItem.getDescription().getSubtitle()).append("\n");
                }
            }

            return builder.toString();
        }

        private String formatTime(long seconds) {
            long minutes = seconds / 60;
            long secs = seconds % 60;
            return String.format("%02d:%02d", minutes, secs);
        }

        private String createProgressBar(long position, int duration) {
            if (duration <= 0) return "[          ]";

            int progress = (int) ((position * 10) / duration);
            StringBuilder bar = new StringBuilder("[");
            for (int i = 0; i < 10; i++) {
                if (i < progress) {
                    bar.append("=");
                } else if (i == progress) {
                    bar.append(">");
                } else {
                    bar.append(" ");
                }
            }
            bar.append("]");
            return bar.toString();
        }

        public static String transform(MediaMetadataCompat data) {
            if (data == null) {
                return null;
            }
            String title = data.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = data.getString(MediaMetadata.METADATA_KEY_ARTIST);
            String albumName = data.getString(MediaMetadata.METADATA_KEY_ALBUM);
            long mediaNumber = data.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER);
            long mediaTotalNumber = data.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS);

            return title + " - " + artist;
        }
    }


}
