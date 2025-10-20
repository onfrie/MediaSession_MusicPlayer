package cn.yinxm.media.ms;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 音乐文件扫描器
 * 只扫描下载目录
 */
public class MusicScanner {
    private static final String TAG = "MusicScanner";

    // 支持的音乐文件格式
    private static final String[] MUSIC_EXTENSIONS = {
            ".mp3", ".wav", ".flac", ".aac", ".m4a", ".ogg", ".wma"
    };

    /**
     * 扫描下载目录中的音乐文件
     */
    public static List<PlayBean> scanDownloadDirectory() {
        List<PlayBean> musicList = new ArrayList<>();

        try {
            // 只扫描下载目录
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            Log.d(TAG, "扫描下载目录: " + downloadDir.getAbsolutePath());

            if (downloadDir.exists() && downloadDir.isDirectory()) {
                scanDirectory(downloadDir, musicList);
            } else {
                Log.w(TAG, "下载目录不存在: " + downloadDir.getAbsolutePath());
            }

        } catch (Exception e) {
            Log.e(TAG, "扫描音乐文件时出错", e);
        }

        Log.d(TAG, "下载目录扫描完成，找到 " + musicList.size() + " 首有效音乐");
        return musicList;
    }

    /**
     * 扫描指定目录下的音乐文件
     */
    private static void scanDirectory(File directory, List<PlayBean> musicList) {
        File[] files = directory.listFiles();
        if (files == null) return;

        int totalFiles = files.length;
        int scannedFiles = 0;

        for (File file : files) {
            scannedFiles++;

            // 每扫描10个文件输出一次进度
            if (scannedFiles % 10 == 0 || scannedFiles == totalFiles) {
                Log.d(TAG, "扫描进度: " + scannedFiles + "/" + totalFiles + " 文件");
            }

            if (file.isDirectory()) {
                // 递归扫描子目录
                scanDirectory(file, musicList);
            } else if (isValidMusicFile(file)) {
                // 添加音乐文件到列表
                PlayBean music = createPlayBean(file);
                if (music != null) {
                    musicList.add(music);
                    if (musicList.size() % 5 == 0) {
                        Log.d(TAG, "已找到 " + musicList.size() + " 首音乐");
                    }
                }
            }
        }
    }

    /**
     * 判断是否为音乐文件（基本检查）
     */
    private static boolean isMusicFile(File file) {
        String name = file.getName().toLowerCase();

        // 过滤没有后缀的文件
        if (!name.contains(".")) {
            Log.d(TAG, "跳过无后缀文件: " + file.getName());
            return false;
        }

        for (String extension : MUSIC_EXTENSIONS) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否为有效的音乐文件（包含所有过滤条件）
     */
    private static boolean isValidMusicFile(File file) {
        // 基本格式检查
        if (!isMusicFile(file)) {
            return false;
        }

        // 快速过滤：先检查文件大小，太小的文件直接跳过
        if (file.length() < 100 * 1024) { // 小于100KB的文件，音乐时长通常很短
            Log.d(TAG, "跳过文件过小的音乐: " + file.getName() + " (" + (file.length()/1024) + "KB)");
            return false;
        }

        // 注释掉时长检查以提高扫描速度
        // 时长检查会在播放时进行，这里不做预检查
        // if (!isValidDuration(file)) {
        //     return false;
        // }

        return true;
    }

    /**
     * 检查音乐文件时长是否有效（>=30秒）
     */
    private static boolean isValidDuration(File file) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(file.getAbsolutePath());

            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long duration = Long.parseLong(durationStr);
                if (duration < 30 * 1000) { // 小于30秒
                    Log.d(TAG, "跳过时长过短的音乐: " + file.getName() + " (" + (duration/1000) + "秒)");
                    return false;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "无法获取音乐时长: " + file.getName(), e);
            return false; // 无法获取时长的文件也跳过
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    Log.e(TAG, "释放MediaMetadataRetriever失败", e);
                }
            }
        }
        return true;
    }

    /**
     * 创建PlayBean对象（优化版本，减少IO操作）
     */
    private static PlayBean createPlayBean(File file) {
        try {
            PlayBean bean = new PlayBean();
            bean.mediaId = file.getAbsolutePath(); // 使用文件路径作为mediaId

            // 直接使用文件名作为标题，避免IO操作
            String fileName = file.getName();
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex > 0) {
                bean.tilte = fileName.substring(0, dotIndex);
            } else {
                bean.tilte = fileName;
            }

            // 暂时使用"未知歌手"，元数据会在播放时获取
            bean.artist = "未知歌手";

            return bean;
        } catch (Exception e) {
            Log.e(TAG, "创建PlayBean失败: " + file.getName(), e);
            return null;
        }
    }

    /**
     * 从文件名提取歌手信息
     */
    private static String extractArtistFromFileName(String fileName) {
        // 简单处理：去掉扩展名作为歌曲名，歌手设为"本地音乐"
        String nameWithoutExt = fileName.substring(0, fileName.lastIndexOf('.'));
        return "本地音乐";
    }

    /**
     * 扫描系统媒体库中的音乐
     */
    public static List<PlayBean> scanSystemMusicLibrary(Context context) {
        List<PlayBean> musicList = new ArrayList<>();

        try {
            String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION
            };

            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);

            if (cursor != null) {
                int titleColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                int artistColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                int dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                int durationColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);

                while (cursor.moveToNext()) {
                    try {
                        PlayBean bean = new PlayBean();
                        bean.tilte = cursor.getString(titleColumn);
                        bean.artist = cursor.getString(artistColumn);
                        if (bean.artist == null || bean.artist.trim().isEmpty()) {
                            bean.artist = "未知歌手";
                        }
                        bean.mediaId = cursor.getString(dataColumn);
                        musicList.add(bean);

                        Log.d(TAG, "系统音乐: " + bean.tilte + " - " + bean.artist);
                    } catch (Exception e) {
                        Log.e(TAG, "处理系统音乐数据时出错", e);
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "扫描系统音乐库时出错", e);
        }

        Log.d(TAG, "系统音乐库扫描完成，找到 " + musicList.size() + " 首音乐");
        return musicList;
    }

    /**
     * 合并两个音乐列表，去除重复项
     */
    public static List<PlayBean> mergeMusicLists(List<PlayBean> downloadList, List<PlayBean> systemList) {
        List<PlayBean> mergedList = new ArrayList<>();

        // 先添加下载目录的音乐
        mergedList.addAll(downloadList);

        // 添加系统音乐库中不重复的音乐
        for (PlayBean systemMusic : systemList) {
            boolean isDuplicate = false;
            for (PlayBean downloadMusic : downloadList) {
                if (systemMusic.tilte.equals(downloadMusic.tilte)) {
                    isDuplicate = true;
                    break;
                }
            }
            if (!isDuplicate) {
                mergedList.add(systemMusic);
            }
        }

        Log.d(TAG, "合并完成，总共 " + mergedList.size() + " 首音乐");
        return mergedList;
    }
}