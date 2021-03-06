package com.idtmessaging.imagethief;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Environment;
import android.support.annotation.Nullable;
import android.util.Log;

import com.idtmessaging.imagethief.util.DiskLruCache;
import com.idtmessaging.imagethief.util.ImageModel;
import com.idtmessaging.imagethief.util.ImageMutableRepository;
import com.idtmessaging.imagethief.util.ImageResizer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static android.os.Environment.DIRECTORY_PICTURES;

/**
 * An {@link IntentService} subclass for handling asynchronous image download requests in
 * a service on a separate handler thread.
 * Created by mary on 23/09/16.
 */
public class ImageThiefService extends IntentService {
    private static final String TAG = "ImageThiefService";
    private static final String ACTION_DOWNLOAD = "com.idtmessaging.imagethief.action.DOWNLOAD";

    private static final String EXTRA_PARAM_URL = "com.idtmessaging.imagethief.extra.PARAM_URL";

    private static final int IMAGE_MAX_DIMEN = 500;

    //Object for working with disk-cache
    private DiskLruCache mDiskLruCache;
    private final Object mDiskCacheLock = new Object();
    private boolean mDiskCacheStarting = true;
    private static final int DISK_CACHE_SIZE = 1024 * 1024 * 10; // 10MB
    private static final String DISK_CACHE_SUBDIR = "images";


    public ImageThiefService() {
        super("ImageThiefService");
    }

    /**
     * Starts this service to start downloading image from url. If
     * the service is already performing a task this action will be queued.
     *
     * @see IntentService
     */
    public static void startDownload(Context context, String url) {
        Intent intent = new Intent(context, ImageThiefService.class);
        intent.setAction(ACTION_DOWNLOAD);
        intent.putExtra(EXTRA_PARAM_URL, url);
        context.startService(intent);
    }


    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {

            //init disk-cache
            if (mDiskLruCache == null) {
                synchronized (mDiskCacheLock) {
                    File cacheDir = getDiskCacheDir(DISK_CACHE_SUBDIR);
                    if (cacheDir == null) {
                        return;
                    }

                    try {
                        mDiskLruCache = DiskLruCache.open(cacheDir, 1, 1, DISK_CACHE_SIZE);
                    } catch (IOException e) {
                        Log.e(TAG, "open disk cache error", e);
                    }
                    mDiskCacheStarting = false; // Finished initialization
                    mDiskCacheLock.notifyAll(); // Wake any waiting threads
                }
            }

            final String action = intent.getAction();
            if (ACTION_DOWNLOAD.equals(action)) {
                handleActionDownload(intent.getStringExtra(EXTRA_PARAM_URL));
            } else {
                throw new UnsupportedOperationException("I'm an Image thief, can't do more!");
            }
        }
    }

    /**
     * Handle action getImage in the provided background thread with the provided
     * parameters. first check Disk-cache, if the image was not saved there then download it from internet.
     */
    private void handleActionDownload(String imageUrl) {
        ImageModel imageModel = new ImageModel();
        imageModel.setUrl(imageUrl);
        OutputStream output = null;
        try {
            // Check disk cache in background thread
            Bitmap bitmap = getBitmapFromDiskCache(imageUrl);
            Log.d(TAG, "handleActionDownload cache:" + (bitmap == null));

            if (bitmap == null) { // Not found in disk cache
                // Process as normal

                // TODO: 24/09/16 Use third-party libraries for http request in real project
                // there are a few library like OpenHttp and Volley(my preference) for handling
                // http request which have lots of benefit such as HTTP/2 support, Transparent GZIP, Response caching and etc.
                // I just used android build-in class because it was simple and enough for this sample project.

                java.net.URL url = new java.net.URL(imageUrl);
                HttpURLConnection connection = (HttpURLConnection) url
                        .openConnection();
                connection.setDoInput(true);
                connection.connect();
                //reading from web
                InputStream input = connection.getInputStream();
                //saving actual image on device
                String baseName = imageUrl.substring(imageUrl.lastIndexOf('/') + 1, imageUrl.length());
                File image = getPictureDir(System.currentTimeMillis() + baseName);
                if (image != null) {
                    output = new FileOutputStream(image);
                    byte[] buffer = new byte[4 * 1024]; // or other buffer size
                    int read;

                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                    output.flush();
                    //save uri of image
                    imageModel.setUri(this, image.getAbsolutePath());

                    // TODO: 23/09/16 I'm resizing actual image for preventing of out-of-memory error
                    // I should use render script for rotating actual image without resizing it.
                    Bitmap tempBittmap = ImageResizer.decodeSampledBitmapFromFile(image.getAbsolutePath(), IMAGE_MAX_DIMEN, IMAGE_MAX_DIMEN);
                    bitmap = rotateImage(tempBittmap, 180);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG,100, bos);
                    byte[] bitmapdata = bos.toByteArray();

                    //write the bytes in file
                    FileOutputStream fos = new FileOutputStream(image);
                    fos.write(bitmapdata);
                    fos.flush();
                    fos.close();

                    // Add final bitmap to caches
                    addBitmapToCache(imageUrl, bitmap);
                }


            } else {
                imageModel.setImageUriFromUrl(getApplicationContext());
            }

            imageModel.setBitmap(getApplicationContext(), bitmap);
        } catch (IOException e) {
            Log.e(TAG, "download image:", e);
            imageModel.setSuccess(false);
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException e) {
                }
            }
        }
        //notify Updatables of finishing task and send final-image to them.
        ImageMutableRepository.getInstance().accept(imageModel);
    }

    /**
     * save downloaded image on disk-cache
     * @param key url of image
     * @param bitmap bitmap of image
     * @throws IOException
     */
    private void addBitmapToCache(String key, Bitmap bitmap) throws IOException {

        if (mDiskLruCache == null) {
            return;
        }

        synchronized (mDiskCacheLock) {
            // Add to disk cache
            if (mDiskLruCache != null) {
                OutputStream out = null;
                try {
                    String hashKey = hashKeyForDisk(key);
                    DiskLruCache.Snapshot snapshot = mDiskLruCache.get(hashKey);
                    if (snapshot == null) {
                        final DiskLruCache.Editor editor = mDiskLruCache.edit(hashKey);
                        if (editor != null) {
                            out = editor.newOutputStream(0);
                            bitmap.compress(
                                    Bitmap.CompressFormat.PNG, 70, out);
                            editor.commit();
                            out.close();
                        }
                    } else {
                        snapshot.getInputStream(0).close();
                    }
                } catch (final IOException e) {
                    Log.e(TAG, "addBitmapToCache - " + e);
                } catch (Exception e) {
                    Log.e(TAG, "addBitmapToCache - " + e);
                } finally {
                    try {
                        if (out != null) {
                            out.close();
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "addBitmapToCache - " + e);
                    }
                }
            }

        }
    }

    /**
     * check Memory-cache for image
     * @param key url of image
     * @return bitmap of image if exit, null otherwise
     * @throws IOException
     */
    @Nullable
    private Bitmap getBitmapFromDiskCache(String key) throws IOException {
        Bitmap bitmap = null;
        synchronized (mDiskCacheLock) {
            while (mDiskCacheStarting) {
                try {
                    mDiskCacheLock.wait();
                } catch (InterruptedException e) {
                }
            }
            if (mDiskLruCache != null) {
                InputStream inputStream = null;
                try {
                    String hashKey = hashKeyForDisk(key);
                    // TODO: 23/09/16 I have a bug here, snapshot always is null
                    final DiskLruCache.Snapshot snapshot = mDiskLruCache.get(hashKey);
                    if (snapshot != null) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Disk cache hit");
                        }
                        inputStream = snapshot.getInputStream(0);
                        if (inputStream != null) {
                            FileDescriptor fd = ((FileInputStream) inputStream).getFD();

                            // Decode bitmap, but we don't want to sample so give
                            // MAX_VALUE as the target dimensions
                            bitmap = ImageResizer.decodeSampledBitmapFromDescriptor(
                                    fd, Integer.MAX_VALUE, Integer.MAX_VALUE);
                        }
                    }
                } catch (final IOException e) {
                    Log.e(TAG, "getBitmapFromDiskCache - " + e);
                } finally {
                    try {
                        if (inputStream != null) {
                            inputStream.close();
                        }
                    } catch (IOException e) {
                    }
                }
            }
            return bitmap;
        }
    }


    // Creates a unique subdirectory of the designated app cache directory. Tries to use external
    // but if not mounted, falls back on internal storage.
    @Nullable
    private File getDiskCacheDir(String uniqueName) {
        // Check if media is mounted or storage is built-in, if so, try and use external cache dir
        // otherwise use internal cache dir
        String cachePath = null;

        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()) || !Environment.isExternalStorageRemovable()) {
            if (getExternalCacheDir() != null) {
                cachePath = getExternalCacheDir().getPath(); // most likely your null value
            }
        } else {
            if (getCacheDir() != null) {
                cachePath = getCacheDir().getPath();
            }
        }

        return cachePath == null ? null : new File(cachePath + File.separator + uniqueName);
    }

    /**
     * create a file in Public Directory if not mounted, otherwise use internal Storage
     * @param uniqueName name of file
     * @return created file or null in error case
     */
    @Nullable
    private File getPictureDir(String uniqueName) {
        // Check if media is mounted or storage is built-in, if so, try and use external dir
        // otherwise use internal cache dir
        String path = null;

        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()) || !Environment.isExternalStorageRemovable()) {
            if (getExternalFilesDir(DIRECTORY_PICTURES) != null) {
                path = getExternalFilesDir(DIRECTORY_PICTURES).getPath(); // most likely your null value
            }
        } else {
            if (getFilesDir() != null) {
                path = getFilesDir().getPath();
            }
        }

        return path == null ? null : new File(path + File.separator + uniqueName);
    }

    private Bitmap rotateImage(Bitmap src, float degree)
    {
        // TODO: 24/09/16 should be done using render-script
        Matrix matrix = new Matrix();
        matrix.postRotate(degree);
        Bitmap bmp = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
        return bmp;
    }

    /**
     * convert string contain uri to hashKey
     * @param key ImageUrl
     * @return hashKey
     */
    private String hashKeyForDisk(String key) {
        String cacheKey;
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(key.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(key.hashCode());
        }
        return cacheKey;
    }


    private String bytesToHexString(byte[] bytes) {
        // http://stackoverflow.com/questions/332079
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

}
