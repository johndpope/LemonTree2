package com.liveness.dflivenesslibrary.process;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Sensor;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import com.dfsdk.liveness.DFLivenessSDK;
import com.liveness.dflivenesslibrary.camera.CameraBase;
import com.liveness.dflivenesslibrary.liveness.DFActionLivenessActivity;
import com.liveness.dflivenesslibrary.liveness.util.Constants;
import com.liveness.dflivenesslibrary.liveness.util.LivenessUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DFActionLivenessProcess implements PreviewCallback {
    private static final String TAG = "DFActionLivenessProcess";
    //TODO will changed to use the web return value
    private static final int DETECT_WAIT_TIME = 1 * 1000;
    private static final boolean DEBUG_PREVIEW = false;

    protected OnLivenessCallBack mListener;
    private boolean mIsKilled = false;
    public boolean mPaused = true;
    private boolean mNV21DataIsReady = false;
    protected byte[] mNv21;
    protected DFLivenessSDK.DFLivenessMotion[] mMotionList;
    private boolean mLiveResult[];
    protected int mCurrentMotion = 0;
    protected DFLivenessSDK mDetector = null;
    private long mStartTime;
    private int mFrameCount = 0;
    private boolean mIsFirstPreviewFrame = true;
    private long mFirstFrameTime;
    private boolean mBeginShowWaitUIBoolean = true;
    private boolean mEndShowWaitUIBoolean = false;
    protected boolean mIsDetectorStartSuccess = false;
    public boolean mIsCreateHandleSuccess = false;
    private ExecutorService mDetectorExecutor;
    protected CameraBase mCameraBase;
    private Intent mIntent;
    private Context mContext;
    private int mWaitTime;
    private Handler mWaitTimeHandler;

    public DFActionLivenessProcess(Activity context, CameraBase cameraBase) {
        mIntent = context.getIntent();
        mContext = context;
        setMotionList();

        mCameraBase = cameraBase;
        initStateAndPreviewCallBack();
        mIsKilled = false;
        startDetector();
//        mWaitTime = DETECT_WAIT_TIME;
//        mWaitTimeHandler = new Handler();

        String sdkVersion = DFLivenessSDK.getSDKVersion();
        LivenessUtils.logI(TAG, "sdkVersion", sdkVersion);
    }

    protected DFLivenessSDK.DFLivenessOutputType getOutputType(Bundle bundle) {
        String output = bundle.getString(DFActionLivenessActivity.OUTTYPE);
        return DFLivenessSDK.DFLivenessOutputType.getOutputTypeByValue(output);
    }

    private int getLivenessConfig(Intent intent) {
        Bundle bundle = intent.getExtras();
        DFLivenessSDK.DFLivenessOutputType outputType = getOutputType(bundle);

        DFLivenessSDK.DFLivenessComplexity complexity = getComplexity(bundle);

        return outputType.getValue() | complexity.getValue();
    }

    protected DFLivenessSDK.DFLivenessComplexity getComplexity(Bundle bundle) {
        if (bundle != null) {
            String complexity = bundle.getString(DFActionLivenessActivity.COMPLEXITY);
            return DFLivenessSDK.DFLivenessComplexity.getComplexityByValue(complexity);
        }
        return DFLivenessSDK.DFLivenessComplexity.WRAPPER_COMPLEXITY_EASY;
    }

    public void startDetector() {

        if (mDetectorExecutor == null) {
            mDetectorExecutor = Executors.newSingleThreadExecutor();
        }

        mDetectorExecutor.execute(new Runnable() {
            @Override
            public void run() {
                while (!mIsKilled) {
                    try {
                        Thread.sleep(15);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (mPaused) {
                        if (mDetector != null) {
                            mDetector.end();
                        }
                        continue;
                    }
                    if (!mPaused && mEndShowWaitUIBoolean) {
                        synchronized (this) {
                            startLivenessIfNeed();
                        }
                        doDetect();
                        mNV21DataIsReady = false;
                    }
                }

                if (mDetector != null) {
                    releaseDetector();
                }
            }
        });
    }

    private void releaseDetector() {
        synchronized (this) {
            if (mDetector != null) {
                mDetector.end();
                mDetector.destroy();
                mDetector = null;
            }
        }
    }

    /**
     * do liveness detecting
     */
    protected void doDetect() {
        DFLivenessSDK.DFStatus status = null;
        if (mDetector != null) {
            try {
                if (mCurrentMotion < mMotionList.length) {
                    if (mIsDetectorStartSuccess) {
                        synchronized (mNv21) {
                            status = mDetector.detect(mNv21,
                                    mCameraBase.getPreviewWidth(),
                                    mCameraBase.getPreviewHeight(),
                                    mCameraBase.getCameraOrientation(),
                                    mMotionList[mCurrentMotion],
                                    isHoldStill(mMotionList[mCurrentMotion]));
                        }
                    }
                }
            } catch (Exception e) {
                if (status != null) {
                    status.setDetectStatus(DFLivenessSDK.DFDetectStatus.INTERNAL_ERROR.getValue());
                }
                e.printStackTrace();
            }
        }

        if (status != null && status.getDetectStatus() != DFLivenessSDK.DFDetectStatus.FRAME_SKIP.getValue()) {
            LivenessUtils.logI(TAG, "getDetectStatus===real", status.getDetectStatus(), "isPassed", status.isPassed());
            if (mListener != null) {
                mListener.onFaceDetect(mMotionList[mCurrentMotion].getValue(), status.isHasFace(), status.isFaceValid());
            }

            if (mCurrentMotion < mMotionList.length) {
                if (status.getDetectStatus() == DFLivenessSDK.DFDetectStatus.TRACKING_MISSED.getValue()) {
                    finishDetect(Constants.LIVENESS_TRACKING_MISSED, mCurrentMotion);
                } else if (status.getDetectStatus() == DFLivenessSDK.DFDetectStatus.MORE_THAN_FACE.getValue()) {
                    finishDetect(Constants.LIVENESS_MORE_THAN_FACE, mCurrentMotion);
                }
            }
            if (status.getDetectStatus() == DFLivenessSDK.DFDetectStatus.PASSED.getValue() && status.isPassed()) {
                LivenessUtils.logI(TAG, "doDetect", "mCurrentMotion", mCurrentMotion, "length", mMotionList.length);
                if (mCurrentMotion < mMotionList.length) {
                    mLiveResult[mCurrentMotion] = true;
                    if (mLiveResult[mCurrentMotion]) {
                        mCurrentMotion++;
                        mDetector.detect(mNv21,
                                mCameraBase.getPreviewWidth(),
                                mCameraBase.getPreviewHeight(),
                                mCameraBase.getCameraOrientation(),
                                DFLivenessSDK.DFLivenessMotion.NONE,
                                isHoldStill(DFLivenessSDK.DFLivenessMotion.NONE));
                        if (mCurrentMotion == mMotionList.length) {
                            finishDetect(Constants.LIVENESS_SUCCESS, mCurrentMotion);
                        } else {
                            restartDetect(true);
                        }
                    }
                }
            }
        }
        mCameraBase.addPreviewCallbackBuffer();
    }

    protected boolean isHoldStill(DFLivenessSDK.DFLivenessMotion motion) {
        boolean isHoldStill = motion == DFLivenessSDK.DFLivenessMotion.HOLD_STILL;
        return isHoldStill;
    }

    private void finishDetect(int livenessSuccess, int mCurrentMotion) {
        stopLiveness();
        mListener.onLivenessDetect(livenessSuccess,
                mCurrentMotion, getLivenessResult(), getVideoResult(), getImageResult());
        if (livenessSuccess == Constants.LIVENESS_SUCCESS) {
            releaseDetector();
            stopDetect();
            stopDetectThread();
        }
    }

    public void stopDetect() {
        mIsKilled = true;
    }

    public void exitDetect() {
        stopDetectThread();
        if (mNv21 != null) {
            mNv21 = null;
        }
    }

    private byte[] getLivenessResult() {
        try {
            synchronized (this) {
                if (mDetector != null) {
                    mDetector.end();
                    return mDetector.getLivenessResult();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    private DFLivenessSDK.DFLivenessImageResult[] getImageResult() {
        try {
            synchronized (this) {
                if (mDetector != null) {
                    mDetector.end();
                    return mDetector.getImageResult();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private byte[] getVideoResult() {
        try {
            synchronized (this) {
                if (mDetector != null) {
                    mDetector.end();
                    return mDetector.getVideoResult();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /*
     * set the WrapperStaticInfo here.
     */
    public void setWrapperStaticInfo() {
        try {
            mDetector.setStaticInfo(DFLivenessSDK.DFWrapperStaticInfo.DEVICE.getValue(), android.os.Build.MODEL);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            mDetector.setStaticInfo(DFLivenessSDK.DFWrapperStaticInfo.OS.getValue(), "Android");
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            mDetector.setStaticInfo(DFLivenessSDK.DFWrapperStaticInfo.SYS_VERSION.getValue(),
                    android.os.Build.VERSION.RELEASE);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            mDetector.setStaticInfo(DFLivenessSDK.DFWrapperStaticInfo.ROOT.getValue(), String.valueOf(LivenessUtils.isRootSystem()));
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            mDetector.setStaticInfo(DFLivenessSDK.DFWrapperStaticInfo.CUSTOMER.getValue(), mContext.getApplicationContext().getPackageName());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initStateAndPreviewCallBack() {
        mCurrentMotion = 0;
        initNv21Data();
        mCameraBase.setPreviewCallback(this);
        mCameraBase.addPreviewCallbackBuffer();
    }

    public void initNv21Data() {
        mNv21 = null;
        mNv21 = new byte[mCameraBase.getPreviewWidth() * mCameraBase.getPreviewHeight() * 3
                / 2];
    }

    public void resetNv21Data() {
        synchronized (mNv21) {
            if (mNv21 != null) {
                for (int i = 0; i < mNv21.length; i++) {
                    mNv21[i] = 0;
                }
            }
        }
    }

    public void onPause() {
        stopLiveness();
        resetNv21Data();
        releaseDetector();
    }

    private void startLivenessIfNeed() {
        if (mDetector == null) {
            try {
                mDetector = new DFLivenessSDK(mContext);
                int createResultCode = mDetector.createHandle();
                mIsCreateHandleSuccess = createResultCode == DFLivenessSDK.DF_LIVENESS_INIT_SUCCESS;
                if (mIsCreateHandleSuccess) {
                    mIsDetectorStartSuccess = mDetector.start(getLivenessConfig(mIntent), mMotionList);
                    setDetectorParameters(mDetector);

                    if (mIsDetectorStartSuccess) {
                        setWrapperStaticInfo();
                    }
                } else {
                    mCameraBase.onErrorHappen(createResultCode);
                }
            } catch (Throwable e) {
                mCameraBase.onErrorHappen(DFActionLivenessActivity.RESULT_CREATE_HANDLE_ERROR);
            }
        }
    }

    public void stopPreview() {
        if (mCameraBase != null) {
            mCameraBase.stopPreview();
        }
    }

    public void startPreview() {
        if (mCameraBase != null) {
            mCameraBase.startPreview();
        }
    }

    protected void setDetectorParameters(DFLivenessSDK detector) {
        // WARNING, setThreshold MUST be invoked after start function, for KEY_TRACK_MISSING threshold depends on
        // the OUTTYPE value.
        /**
         * Set liveness motion's threshold
         * WARNING: this MUST be invoked after @start(int config, DFLivenessMotion[] motions) function.
         * for KEY_TRACK_MISSING threshold depends on the OUTTYPE value.
         *
         * @param key, see DFLivenessKey definition:
         *         KEY_BLINK_KEY: The blink motion's key to set threshold, default is 0.f
         *         KEY_MOUTH_KEY: The mouth motion's key to set threshold, default is 0.f
         *         KEY_YAW_KEY: The yaw motion's key to set threshold, default is 0.f
         *         KEY_PITCH_KEY: The pitch motion's key to set threshold, default is 0.f
         *         KEY_HOLD_STILL: The hold_still motion's key to set threshold, default is 0.f
         *         KEY_TRACK_MISSING: Whether continuing to do next motion after the face has lost, default is 0.f
         *         KEY_HOLD_STILL_FRAME: The interval number frames which HOLD_STILL motion do checking face position, default is 10
         *         KEY_HOLD_STILL_POS: The IOU value which calculate the current face with the initial face position. default is 0.95
         * @param value, [KEY_BLINK_KEY, KEY_MOUTH_KEY, KEY_YAW_KEY, KEY_PITCH_KEY, KEY_HOLD_STILL_POS]'s value must be in [0.f, 1.f], if key is KEY_TRACK_MISSING, the value 0.0f means
         *               that TRACK_MISSING is false, otherwise is true.
         */
        detector.setThreshold(DFLivenessSDK.DFLivenessKey.KEY_BLINK_KEY, 0.7f);
        detector.setThreshold(DFLivenessSDK.DFLivenessKey.KEY_MOUTH_KEY, 0.7f);
        detector.setThreshold(DFLivenessSDK.DFLivenessKey.KEY_PITCH_KEY, 0.7f);
        detector.setThreshold(DFLivenessSDK.DFLivenessKey.KEY_YAW_KEY, 0.7f);
        detector.setThreshold(DFLivenessSDK.DFLivenessKey.KEY_HOLD_STILL, 0.00f);

        if (haveHoldStillMotion()) {
            setHoldStillDetectorParameters(detector);
            setDetectionRegion(detector);
        }
    }

    protected void setHoldStillDetectorParameters(DFLivenessSDK detector) {
//        detector.setThreshold(DFLivenessSDK.DFLivenessKey.KEY_TRACK_MISSING, 1.f);
        detector.setThreshold(DFLivenessSDK.DFLivenessKey.KEY_HOLD_STILL_DETECT_NUMBER, 1.f);
        detector.setThreshold(DFLivenessSDK.DFLivenessKey.KEY_HOLD_STILL_FACE_RET_MAX_RATE, 0.25f);
    }

    /**
     * This function is used to calculate the circle region in detection UI.
     * The region is used to limit the detection range of detect, if there is a human face in this region,
     * we will gather clear images of this face.
     * The default region is the image size.
     *
     * @param detector
     */
    protected void setDetectionRegion(DFLivenessSDK detector) {

        try {
            RectF region = new RectF();

            float scale = (float) mCameraBase.getPreviewWidth() / mCameraBase.getOverlapHeight();

            Rect rect = mCameraBase.getScanRect();
            region.left = (mCameraBase.getOverlapHeight() - rect.bottom + 0.f) * scale;
            region.top = (mCameraBase.getOverlapWidth() - rect.right + 0.f) * scale;
            region.right = region.left + rect.width() * scale;
            region.bottom = region.top + rect.height() * scale;

            int margin = (int) (rect.width() * 0.20);
            region.left = region.left - margin;
            region.top = region.top - margin;
            region.right = region.right + margin;
            region.bottom = region.bottom + margin;
            detector.setDetectRegion(region);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void stopDetectThread() {
        mIsCreateHandleSuccess = false;
        if (mDetectorExecutor != null) {
            mDetectorExecutor.shutdown();
            try {
                mDetectorExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            mDetectorExecutor = null;
        }
    }

    void restartDetect(boolean bRestartTime) {
        if (bRestartTime) {
            mListener.onLivenessDetect(mMotionList[mCurrentMotion].getValue(), mCurrentMotion, null, null, null);
        }
    }

    public void resetStatus(boolean fAlert) {
        boolean bRestartTime = fAlert;
        if (mCurrentMotion > 0) {
            bRestartTime = true;
        }
        resetLivenessResult();
        mCurrentMotion = 0;
        restartDetect(bRestartTime);
    }

    private void resetLivenessResult() {
        int count = mLiveResult.length;
        for (int i = 0; i < count; i++) {
            mLiveResult[i] = false;
        }
    }

    private void setMotionList() {
        mMotionList = getMotionList();

        if (mMotionList != null && mMotionList.length > 0) {
            mLiveResult = new boolean[mMotionList.length];
            for (int i = 0; i < mMotionList.length; i++) {
                mLiveResult[i] = false;
            }
        }
    }

    protected boolean haveHoldStillMotion() {
        boolean haveHoldStillMotion = false;
        if (mMotionList != null) {
            for (DFLivenessSDK.DFLivenessMotion livenessMotion : mMotionList) {
                if (livenessMotion == DFLivenessSDK.DFLivenessMotion.HOLD_STILL) {
                    haveHoldStillMotion = true;
                    break;
                }
            }
        }
        return haveHoldStillMotion;
    }

    protected DFLivenessSDK.DFLivenessMotion[] getMotionList() {
        return LivenessUtils.getMctionOrder(mIntent
                .getStringExtra(DFActionLivenessActivity.EXTRA_MOTION_SEQUENCE));
    }

    public void registerLivenessDetectCallback(OnLivenessCallBack callback) {
        mListener = callback;
    }

    public void onTimeEnd() {
        finishDetect(Constants.LIVENESS_TIME_OUT, mCurrentMotion);
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (DEBUG_PREVIEW) {
            debugFps();
        }
        if (mIsFirstPreviewFrame) {
            mFirstFrameTime = System.currentTimeMillis();
            mIsFirstPreviewFrame = false;
        }
        long intervalTime = System.currentTimeMillis() - mFirstFrameTime;
        if (intervalTime <= DETECT_WAIT_TIME) {
            if (mBeginShowWaitUIBoolean) {
                mListener.onLivenessDetect(Constants.DETECT_BEGIN_WAIT,
                        1, null, null, null);
                mBeginShowWaitUIBoolean = false;
            }
            mCameraBase.addPreviewCallbackBuffer();
        } else {
            if (!mEndShowWaitUIBoolean) {
                mListener
                        .onLivenessDetect(Constants.DETECT_END_WAIT, 1, null, null, null);
                mEndShowWaitUIBoolean = true;
                startLiveness();
            }
            if (!mPaused && !mNV21DataIsReady) {
                synchronized (mNv21) {
                    if (data != null && mNv21 != null
                            && mNv21.length >= data.length) {
                        System.arraycopy(data, 0, mNv21, 0, data.length);
                        mNV21DataIsReady = true;
                    }
                }
            }
        }
    }

    public void setWaitTime(int waitTime) {
        this.mWaitTime = waitTime;
    }

    private void sendTimeCount() {
        mWaitTimeHandler.postDelayed(mWaitTimeRunnable, 1000);
    }

    private Runnable mWaitTimeRunnable = new Runnable() {
        @Override
        public void run() {
            mWaitTime -= 1;
            if (mWaitTime > 0) {
                sendTimeCount();
            }
            if (mListener != null) {
                mListener.onDetectCountdown(mWaitTime);
            }
        }
    };

    public interface OnLivenessCallBack {
        void onLivenessDetect(int value, int status, byte[] livenessEncryptResult,
                              byte[] videoResult, DFLivenessSDK.DFLivenessImageResult[] imageResult);

        void onFaceDetect(int value, boolean hasFace, boolean faceValid);

        void onDetectCountdown(int count);
    }

    public void stopLiveness() {
        mPaused = true;
    }

    public void startLiveness() {
        resetStatus(false);
        mPaused = false;
        startPreview();
    }

    public void addSequentialInfo(int type, float[] values) {
        if (!mPaused && mDetector != null
                && mIsCreateHandleSuccess) {
            StringBuilder sb = new StringBuilder();
            sb.append(values[0])
                    .append(" ")
                    .append(values[1])
                    .append(" ")
                    .append(values[2])
                    .append(" ");
            DFLivenessSDK.DFWrapperSequentialInfo sequentialInfo = null;
            switch (type) {
                case Sensor.TYPE_MAGNETIC_FIELD:
                    sequentialInfo = DFLivenessSDK.DFWrapperSequentialInfo.MAGNETIC_FIELD;
                    break;
                case Sensor.TYPE_ACCELEROMETER:
                    sequentialInfo = DFLivenessSDK.DFWrapperSequentialInfo.ACCLERATION;
                    break;
                case Sensor.TYPE_ROTATION_VECTOR:
                    sequentialInfo = DFLivenessSDK.DFWrapperSequentialInfo.ROTATION_RATE;
                    break;
                case Sensor.TYPE_GRAVITY:
                    sequentialInfo = DFLivenessSDK.DFWrapperSequentialInfo.GRAVITY;
                    break;
            }
            try {
                if (sequentialInfo != null) {
                    mDetector
                            .addSequentialInfo(sequentialInfo
                                    .getValue(), sb.toString());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            sb = null;
        }
    }

    private void debugFps() {
        if (mFrameCount == 0) {
            mStartTime = System.currentTimeMillis();
        }
        mFrameCount++;
        long testTime = System.currentTimeMillis() - mStartTime;
        if (testTime > 1000) {
            Log.i(TAG, "onPreviewFrame FPS = " + mFrameCount);
            Toast.makeText(mContext, "FPS: " + mFrameCount, Toast.LENGTH_SHORT).show();
            mFrameCount = 0;
        }
    }
}