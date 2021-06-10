package com.example.camera_app;

import androidx.fragment.app.Fragment;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Camera2Fragment extends Fragment implements View.OnClickListener{
    private static String TAG = "frag";
    private String mCameraId;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    private AutoFitTextureView mTextureView;
    private CameraCaptureSession mCaptureSession;
    private CameraDevice mCameraDevice;
    private Size mPreviewSize;
    private Semaphore mCameraOpenCloseLock= new Semaphore(1);;
    private ImageReader mImageReader;
    private File mFile;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CaptureRequest mPreviewRequest;
    long prev_time = 0;
    int init_count = 0;
    int bins = 32;
    Complex[] intensity_frames = new Complex[bins];
    double frames_per_second = 0.0;
    int count = 0;


    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            Log.d(TAG, "onOpened");
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            getActivity().onBackPressed();
        }

    };

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener =
            new TextureView.SurfaceTextureListener() {

                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                    Log.d(TAG, "onSurfaceTextureAvailable");
                    openCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {

                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                    Log.d(TAG, "on st updated");
                }

            };

    private final CameraCaptureSession.CaptureCallback mCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                private void process(CaptureResult result) {
                }

                @Override
                public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request,
                                                CaptureResult partialResult) {
                    process(partialResult);
                }

                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                               TotalCaptureResult result) {
                    process(result);
                }

            };

    private void createCameraPreviewSession() {
        Log.d(TAG, "createCamPreview");
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            Log.e(TAG, "mPreviewSize.getWidth(): " + mPreviewSize.getWidth() + ", mPreviewSize.getHeight(): "
                    + mPreviewSize.getHeight());

            Surface surface = new Surface(texture);
            Surface mImageSurface = mImageReader.getSurface();
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(mImageSurface);
            mPreviewRequestBuilder.addTarget(surface);


            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(mImageSurface, surface),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            Log.d(TAG, "onConfigured");
                            if (mCameraDevice == null) return;

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_STATE_ACTIVE_SCAN);
                                // Flash is automatically enabled when necessary.
//                                mPreviewRequestBuilder.set(CONTROL_AE_MODE, CONTROL_AE_MODE_ON_AUTO_FLASH); // no need for flash now

                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
                                        mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                            Toast.makeText(getActivity(), "Failed", Toast.LENGTH_SHORT).show();
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void openCamera(int width, int height) {
        Log.d(TAG, "openCamera");
        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        CameraManager manager = (CameraManager) getActivity().getSystemService(getActivity().CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }

            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        } catch(SecurityException s) {
            s.printStackTrace();
        }
    }

    private void closeCamera() {
        Log.d(TAG, "closeCamera");
        try {
            mCameraOpenCloseLock.acquire();
            if (mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        Log.d(TAG, "startBackgroundThread");
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        Log.d(TAG, "stop bg thread");
        try {
            mBackgroundThread.quitSafely();
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (NullPointerException ex) {
            ex.printStackTrace();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        }
    }

    private void setUpCameraOutputs(int width, int height) {
        Log.d(TAG, "set up outputs");
        CameraManager manager = (CameraManager) getActivity().getSystemService(getActivity().CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) continue;

                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                int[] res = map.getOutputFormats();
                for(int i = 0; i < res.length; i++){
                    Log.d(TAG, "IMage format" + res[i]);
                }

                if (map.isOutputSupportedFor(ImageFormat.RGB_565))
                    Log.d(TAG, "RGB supported");
                else if (map.isOutputSupportedFor(ImageFormat.FLEX_RGB_888))
                    Log.d(TAG, "RGB FLex supported");





                // For still image captures, we use the largest available size.
                List<Size> outputSizes = Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888));

                Size largest = Collections.max(outputSizes, new CompareSizesByArea());

                mImageReader = ImageReader.newInstance(largest.getWidth() / 16, largest.getHeight() / 16, ImageFormat.YUV_420_888, 2);
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);
                // Danger, W.R.! Attempting to use too large a preview size could exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height, largest);
                Log.d(TAG, "WIDTH: " + mPreviewSize.getWidth() + " HEIGHT: " + mPreviewSize.getHeight());
                //We fit the aspect ratio of TextureView to the size of preview we picked.

                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                   mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } else {
                mTextureView.setAspectRatio( mPreviewSize.getHeight(), mPreviewSize.getWidth()); //portrait only
                }

                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    private void configureTransform(int viewWidth, int viewHeight) {
        Log.d(TAG, "config transforms");

        if (mTextureView == null || mPreviewSize == null) return;

        int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {

                @Override
                public void onImageAvailable(ImageReader reader) {
                    //Log.e(TAG, "onImageAvailable: " );
                    Image img = null;
                    img = reader.acquireLatestImage();

                    long curr_time = img.getTimestamp();
                    long diff = (curr_time - prev_time)/ 1000000;

                    prev_time = curr_time;

                    if (count == 0){
                        frames_per_second = 1000.00/diff;
                    } else{
                        frames_per_second = ((frames_per_second * count) + (1000.00/diff))/(count+1);
                    }
                    count++;
                    Log.d("Frame diff", "" + diff);
                    Log.d("Frame rate", "" + frames_per_second);

                    /*
                    //JPEG PROCESSING
                    ByteBuffer buffer = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.capacity()];
                    buffer.get(bytes);
                    Bitmap bitmapImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, null);

                    int width = img.getWidth();
                    int height = img.getHeight();
                    long sumIntensity = 0;
                    long sumR = 0;
                    for(int row = 0; row < height; row++){
                        for(int col = 0; col < width; col++){
                            Color c = bitmapImage.getColor(col, row);
                            sumIntensity += c.red()*255 + c.green()*255 + c.blue()*255;
                            sumR += c.red()*255;
                        }
                    }
                    double avgIntensity = ((double) sumIntensity) / (width*height);
                    double avgR = ((double) sumR/(width*height));
                    Log.d(TAG, "Frame Intensity = " + avgIntensity);
                    Log.d(TAG, "Frame R = " + avgR);
                    */
                    //img.close();


                    //YUV_420_888 PROCESSING
                    ByteBuffer bufferY = img.getPlanes()[0].getBuffer();
                    byte[] dataY = new byte[bufferY.remaining()];
                    bufferY.get(dataY);

                    ByteBuffer bufferU = img.getPlanes()[1].getBuffer();
                    byte[] dataU = new byte[bufferU.remaining()];
                    bufferU.get(dataU);

                    ByteBuffer bufferV = img.getPlanes()[2].getBuffer();
                    byte[] dataV = new byte[bufferV.remaining()];
                    bufferV.get(dataV);

                    int width = img.getWidth();
                    int height = img.getHeight();
                    int sectionSize = width/2;
                    img.close();

                    int i, j;
                    long sumIntensity = 0;
                    long sumR = 0;
                    for(int row = 0; row < height; row++){
                        for(int col = 0; col < width; col++){
                            i = row * width + col;
                            int section = row/2;
                            j = section * sectionSize + (col/2);
                            int r = clamp(dataY[i] + (1.370705 * (dataV[j] - 128)));
                            int g = clamp(dataY[i] - (0.698001 * (dataV[j] - 128)) - (0.337633 * (dataU[j] - 128)));
                            int b = clamp(dataY[i] + (1.732446 * (dataU[j] - 128)));
                            sumIntensity += r + g + b;
                            sumR += b;
                        }
                      }

                    double avgIntensity = ((double) sumIntensity) / (width*height);
                    double avgR = ((double) sumR/(width*height));;

                    Log.d(TAG, "Frame Intensity = " + avgIntensity);
                    Log.d(TAG, "Frame R = " + avgR);

                    //INITIALINSING INTENSITY ARRAY AND CALCULATING FFT
                    if (init_count != bins){
                        intensity_frames[init_count] = new Complex(avgIntensity, 0.0);
                        init_count++;
                    } else {
                        for(int k = 0; k < bins-1; k++)
                            intensity_frames[k] = intensity_frames[k+1];
                        intensity_frames[bins-1] = new Complex(avgIntensity, 0.0);

                        Complex[] fftResult = FFT.fft(intensity_frames);
                        int max_index = -1;
                        double max = 0;
                        for(int k = 0; k < bins/2; k++){
                            if (fftResult[k].abs() > max){
                                max = fftResult[k].abs();
                                max_index= k;
                            }
                        }
                        double peakHz = max_index*(frames_per_second/(bins/2));
                        Log.d(TAG, "fftresult " + fftResult[0].abs() + " " + fftResult[1].abs() + " " + fftResult[2].abs() + " " + fftResult[3].abs()+ " " + fftResult[4].abs() + " " + fftResult[5].abs());
                        Log.d(TAG, "fftresult " + peakHz);
                    }




/*
                    try {
                        if (img == null) throw new NullPointerException("cannot be null");
                        ByteBuffer buffer = img.getPlanes()[0].getBuffer();
                        byte[] data = new byte[buffer.remaining()];
                        buffer.get(data);
                        int width = img.getWidth();
                        int height = img.getHeight();

                    } catch (NullPointerException ex) {
                        ex.printStackTrace();
                    } finally {

                        Log.e(TAG, "in the finally! ------------");
                        if (img != null)
                            img.close();

                    }
*/
                }

            };

    private int clamp(double val){
        return Math.max(0, Math.min((int) val, 255));
    }
    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        Log.d(TAG, "optimal size");
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.picture: {
                Log.d(TAG, "pic");
                break;
            }
            case R.id.info: {
                Log.d(TAG, "info");

                break;
            }
        }
    }

    private static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Log.d(TAG, "oncreateview");
        View rootView = inflater.inflate(R.layout.fragment_camera2, container, false);

        return rootView;
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        view.findViewById(R.id.picture).setOnClickListener(this);
        view.findViewById(R.id.info).setOnClickListener(this);
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mFile = new File(getActivity().getExternalFilesDir(null), "pic.jpg");
    }



    @Override
    public void onResume() {
        super.onResume();
        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        startBackgroundThread();
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        Log.e(TAG, "onPause");
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    public static Fragment newInstance() {
        Log.d(TAG, "newInstance" );
        return new Camera2Fragment();
    }
}
