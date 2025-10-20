package cn.yinxm.media.ms;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import android.os.SystemClock;

public class DemoActivity extends AppCompatActivity {
    private static final String TAG = "DemoActivity";

    // 权限请求相关常量
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String[] REQUIRED_PERMISSIONS = {
        android.Manifest.permission.READ_EXTERNAL_STORAGE,
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    View mViewController;
    private CheckBox btnPlay;
    private TextView textTitle;
    private SeekBar seekBar;
    private TextView textCurrentTime;
    private TextView textTotalTime;
    private Timer progressUpdateTimer;
    private boolean isUserSeeking = false;

    private RecyclerView recyclerView;
    private List<MediaBrowserCompat.MediaItem> list;
    private DemoAdapter demoAdapter;
    private LinearLayoutManager layoutManager;

    private MediaBrowserCompat mBrowser;
    private MediaControllerCompat mController;
    private String mediaId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_demo);

        // 检查权限
        if (!checkAndRequestPermissions()) {
            // 如果权限未获取，直接初始化UI但提示用户
            Toast.makeText(this, "需要存储权限才能扫描本地音乐", Toast.LENGTH_LONG).show();
        }

        startService(new Intent(this, MusicService.class)); // 避免ui unbind后，后台播放音乐停止
        mBrowser = new MediaBrowserCompat(this, new ComponentName(this, MusicService.class),// 绑定浏览器服务
                BrowserConnectionCallback,// 设置连接回调
                null);

        mViewController = findViewById(R.id.view_controller);
        btnPlay = findViewById(R.id.btn_play);
        textTitle = (TextView) findViewById(R.id.text_title);
        seekBar = findViewById(R.id.seek_bar);
        textCurrentTime = findViewById(R.id.text_current_time);
        textTotalTime = findViewById(R.id.text_total_time);

        // 设置进度条监听
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    // 更新当前时间显示
                    textCurrentTime.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isUserSeeking = false;
                // 用户拖拽进度条时，跳转到指定位置
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

        list = new ArrayList<>();
        layoutManager = new LinearLayoutManager(this);
        layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        demoAdapter = new DemoAdapter(this, list);
        demoAdapter.setOnItemClickListener(new DemoAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(View view, int position) {
                Bundle bundle = new Bundle();
                bundle.putInt("playPosition", position);

                String mediaId = list.get(position).getMediaId();
                Uri uri;

                if (mediaId.startsWith("/")) {
                    // 文件路径，直接创建Uri
                    uri = Uri.fromFile(new java.io.File(mediaId));
                } else {
                    // 内置资源，转换为整数
                    try {
                        uri = rawToUri(Integer.valueOf(mediaId));
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "无法解析mediaId: " + mediaId, e);
                        return;
                    }
                }

                mController.getTransportControls().playFromUri(uri, bundle);
            }

            @Override
            public void onItemLongClick(View view, int position) {

            }
        });

        recyclerView = (RecyclerView) findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(demoAdapter);

    }

    @Override
    protected void onStart() {
        super.onStart();

        // 确保权限已获取后再连接MediaBrowser
        if (hasAllPermissions()) {
            //Browser发送连接请求
            mBrowser.connect();
        } else {
            Log.w(TAG, "权限未获取，无法连接MediaBrowser");
            Toast.makeText(this, "请先授予存储权限", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mController != null) {
            mController.unregisterCallback(mControllerCallback);
        }
        if (mBrowser != null) {
            mBrowser.unsubscribe(mediaId);
            mBrowser.disconnect();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopProgressTimer();
    }

    public void clickEvent(View view) {
        int id = view.getId();
        if (id == R.id.btn_play) {
            if (mController != null) {
                handlerPlayEvent();
            }
        } else if (id == R.id.btn_previous) {
            if (mController != null) {
                mController.getTransportControls().skipToPrevious();
                Log.d(TAG, "点击上一首按钮");
            }
        } else if (id == R.id.btn_next) {
            if (mController != null) {
                mController.getTransportControls().skipToNext();
                Log.d(TAG, "点击下一首按钮");
            }
        }
    }

    /**
     * 处理播放按钮事件
     */
    private void handlerPlayEvent() {
        switch (mController.getPlaybackState().getState()) {
            case PlaybackStateCompat.STATE_PLAYING:
                mController.getTransportControls().pause();
                break;
            case PlaybackStateCompat.STATE_PAUSED:
                mController.getTransportControls().play();
                break;
            default:
                mController.getTransportControls().playFromSearch("", null);
                break;
        }
    }

    private void updatePlayState(PlaybackStateCompat state) {
        if (state == null) {
            return;
        }
        switch (state.getState()) {
            case PlaybackStateCompat.STATE_NONE://无任何状态
                textTitle.setText("");
                btnPlay.setChecked(true);
                stopProgressTimer();
                break;
            case PlaybackStateCompat.STATE_PAUSED:
                btnPlay.setChecked(true);
                stopProgressTimer();
                // 暂停时也要更新一次进度，显示准确的暂停位置
                updateProgressFromState(state);
                break;
            case PlaybackStateCompat.STATE_PLAYING:
                btnPlay.setChecked(false);
                startProgressTimer();
                // 开始播放时立即更新一次进度
                updateProgressFromState(state);
                break;
        }
    }

    private void updatePlayMetadata(MediaMetadataCompat metadata) {
        if (metadata == null) {
            return;
        }
        mViewController.setVisibility(View.VISIBLE);
        textTitle.setText(metadata.getDescription().getTitle() + " - " + metadata.getDescription().getSubtitle());

        // 更新总时长
        long duration = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
        textTotalTime.setText(formatTime((int) (duration / 1000)));
        seekBar.setMax(1000);
    }

    /**
     * 连接状态的回调接口，连接成功时会调用onConnected()方法
     */
    private MediaBrowserCompat.ConnectionCallback BrowserConnectionCallback =
            new MediaBrowserCompat.ConnectionCallback() {
                @Override
                public void onConnected() {
                    Log.e(TAG, "onConnected------");
                    if (mBrowser.isConnected()) {
                        //mediaId即为MediaBrowserService.onGetRoot的返回值
                        //若Service允许客户端连接，则返回结果不为null，其值为数据内容层次结构的根ID
                        //若拒绝连接，则返回null
                        mediaId = mBrowser.getRoot();

                        //Browser通过订阅的方式向Service请求数据，发起订阅请求需要两个参数，其一为mediaId
                        //而如果该mediaId已经被其他Browser实例订阅，则需要在订阅之前取消mediaId的订阅者
                        //虽然订阅一个 已被订阅的mediaId 时会取代原Browser的订阅回调，但却无法触发onChildrenLoaded回调

                        //ps：虽然基本的概念是这样的，但是Google在官方demo中有这么一段注释...
                        // This is temporary: A bug is being fixed that will make subscribe
                        // consistently call onChildrenLoaded initially, no matter if it is replacing an existing
                        // subscriber or not. Currently this only happens if the mediaID has no previous
                        // subscriber or if the media content changes on the service side, so we need to
                        // unsubscribe first.
                        //大概的意思就是现在这里还有BUG，即只要发送订阅请求就会触发onChildrenLoaded回调
                        //所以无论怎样我们发起订阅请求之前都需要先取消订阅
                        mBrowser.unsubscribe(mediaId);
                        //之前说到订阅的方法还需要一个参数，即设置订阅回调SubscriptionCallback
                        //当Service获取数据后会将数据发送回来，此时会触发SubscriptionCallback.onChildrenLoaded回调
                        mBrowser.subscribe(mediaId, mBrowserSubscriptionCallback);

                        try {
                            mController = new MediaControllerCompat(DemoActivity.this, mBrowser.getSessionToken());
                            mController.registerCallback(mControllerCallback);
                            if (mController.getMetadata() != null) {
                                updatePlayMetadata(mController.getMetadata());
                                updatePlayState(mController.getPlaybackState());
                            }
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                }

                @Override
                public void onConnectionFailed() {
                    Log.e(TAG, "连接失败！");
                }
            };
    /**
     * 向媒体浏览器服务(MediaBrowserService)发起数据订阅请求的回调接口
     */
    private final MediaBrowserCompat.SubscriptionCallback mBrowserSubscriptionCallback =
            new MediaBrowserCompat.SubscriptionCallback() {
                @Override
                public void onChildrenLoaded(@NonNull String parentId,
                                             @NonNull List<MediaBrowserCompat.MediaItem> children) {
                    Log.e(TAG, "onChildrenLoaded------" + children);
                    list.clear();
                    //children 即为Service发送回来的媒体数据集合
                    for (MediaBrowserCompat.MediaItem item : children) {
                        Log.e(TAG, item.getDescription().getTitle().toString());
                        list.add(item);
                    }
                    demoAdapter.notifyDataSetChanged();

                    // 如果有音乐文件，显示控制栏
                    if (children != null && children.size() > 0) {
                        Log.d(TAG, "找到 " + children.size() + " 首音乐，显示控制栏");
                        if (mViewController != null) {
                            mViewController.setVisibility(View.VISIBLE);
                        }
                    } else {
                        Log.w(TAG, "没有找到音乐文件");
                    }
                }
            };

    /**
     * 媒体控制器控制播放过程中的回调接口，可以用来根据播放状态更新UI
     */
    private final MediaControllerCompat.Callback mControllerCallback =
            new MediaControllerCompat.Callback() {
                /***
                 * 音乐播放状态改变的回调
                 * @param state
                 */
                @Override
                public void onPlaybackStateChanged(PlaybackStateCompat state) {
                    updatePlayState(state);
                    // 实时更新进度条
                    updateProgressFromState(state);
                }

                /**
                 * 播放音乐改变的回调
                 * @param metadata
                 */
                @Override
                public void onMetadataChanged(MediaMetadataCompat metadata) {
                    updatePlayMetadata(metadata);
                }

            };

    private Uri rawToUri(int id) {
        String uriStr = "android.resource://" + getPackageName() + "/" + id;
        return Uri.parse(uriStr);
    }

    /**
     * 格式化时间显示
     */
    private String formatTime(int seconds) {
        int minutes = seconds / 60;
        int secs = seconds % 60;
        return String.format("%02d:%02d", minutes, secs);
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
                        updateProgress();
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
     * 更新进度
     */
    private void updateProgress() {
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
                textCurrentTime.setText(formatTime((int) (position / 1000)));

                Log.d(TAG, "更新进度: " + formatTime((int) (position / 1000)) +
                      " / " + formatTime((int) (duration / 1000)) +
                      " (进度: " + progress + "/1000)");
            } else {
                Log.w(TAG, "音乐时长为0，无法更新进度条");
            }
        }
    }

    /**
     * 检查是否已获取所有权限
     */
    private boolean hasAllPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }

        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查并请求权限
     */
    private boolean checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Android 6.0以下不需要运行时权限
            return true;
        }

        List<String> missingPermissions = new ArrayList<>();
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }

        if (missingPermissions.isEmpty()) {
            // 所有权限都已获取
            return true;
        }

        // 请求缺失的权限
        ActivityCompat.requestPermissions(
                this,
                missingPermissions.toArray(new String[0]),
                PERMISSION_REQUEST_CODE
        );
        return false;
    }

    /**
     * 权限请求结果回调
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                // 权限获取成功，重新连接MediaBrowser以刷新音乐列表
                Log.d(TAG, "存储权限获取成功，重新扫描音乐文件");
                Toast.makeText(this, "权限获取成功，正在扫描音乐文件", Toast.LENGTH_SHORT).show();

                // 重新连接MediaBrowser以刷新音乐列表
                if (mBrowser != null && mBrowser.isConnected()) {
                    mBrowser.unsubscribe(mBrowser.getRoot());
                    mBrowser.subscribe(mBrowser.getRoot(), mBrowserSubscriptionCallback);
                }
            } else {
                // 权限获取失败
                Log.w(TAG, "存储权限获取失败，无法扫描本地音乐文件");
                Toast.makeText(this, "需要存储权限才能扫描本地音乐文件", Toast.LENGTH_LONG).show();
            }
        }
    }
}
