/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.media.AudioManager;
import android.media.CameraProfile;
import android.media.SoundPool;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.OrientationEventListener;
import android.view.View;

import com.android.camera.PhotoModule.NamedImages.NamedEntity;
import com.android.camera.app.AppController;
import com.android.camera.app.CameraAppUI;
import com.android.camera.app.CameraProvider;
import com.android.camera.app.MediaSaver;
import com.android.camera.app.MemoryManager;
import com.android.camera.app.MemoryManager.MemoryListener;
import com.android.camera.app.MotionManager;
import com.android.camera.debug.Log;
import com.android.camera.exif.ExifInterface;
import com.android.camera.exif.ExifTag;
import com.android.camera.exif.Rational;
import com.android.camera.hardware.HardwareSpec;
import com.android.camera.hardware.HardwareSpecImpl;
import com.android.camera.module.ModuleController;
import com.android.camera.remote.RemoteCameraModule;
import com.android.camera.settings.CameraPictureSizesCacher;
import com.android.camera.settings.Keys;
import com.android.camera.settings.ResolutionUtil;
import com.android.camera.settings.SettingsManager;
import com.android.camera.settings.SettingsUtil;
import com.android.camera.ui.CountDownView;
import com.android.camera.ui.TouchCoordinate;
import com.android.camera.util.ApiHelper;
import com.android.camera.util.CameraUtil;
import com.android.camera.util.GcamHelper;
import com.android.camera.util.GservicesHelper;
import com.android.camera.util.SessionStatsCollector;
import com.android.camera.util.UsageStatistics;
import com.android.camera.widget.AspectRatioSelector;
import com.android.camera2.R;
import com.android.ex.camera2.portability.CameraAgent;
import com.android.ex.camera2.portability.CameraAgent.CameraAFCallback;
import com.android.ex.camera2.portability.CameraAgent.CameraAFMoveCallback;
import com.android.ex.camera2.portability.CameraAgent.CameraPictureCallback;
import com.android.ex.camera2.portability.CameraAgent.CameraProxy;
import com.android.ex.camera2.portability.CameraAgent.CameraShutterCallback;
import com.android.ex.camera2.portability.CameraCapabilities;
import com.android.ex.camera2.portability.CameraDeviceInfo.Characteristics;
import com.android.ex.camera2.portability.CameraSettings;
import com.android.ex.camera2.portability.Size;
import com.google.common.logging.eventprotos;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

public class PhotoModule
        extends CameraModule
        implements PhotoController,
        ModuleController,
        MemoryListener,
        FocusOverlayManager.Listener,
        SensorEventListener,
        SettingsManager.OnSettingChangedListener,
        RemoteCameraModule,
        CountDownView.OnCountDownStatusListener {

    public static final String PHOTO_MODULE_STRING_ID = "PhotoModule";

    private static final Log.Tag TAG = new Log.Tag(PHOTO_MODULE_STRING_ID);

    // We number the request code from 1000 to avoid collision with Gallery.
    private static final int REQUEST_CROP = 1000;

    // Messages defined for the UI thread handler.
    private static final int MSG_FIRST_TIME_INIT = 1;
    private static final int MSG_SET_CAMERA_PARAMETERS_WHEN_IDLE = 2;

    // The subset of parameters we need to update in setCameraParameters().
    private static final int UPDATE_PARAM_INITIALIZE = 1;
    private static final int UPDATE_PARAM_ZOOM = 2;
    private static final int UPDATE_PARAM_PREFERENCE = 4;
    private static final int UPDATE_PARAM_ALL = -1;

    // This is the delay before we execute onResume tasks when coming
    // from the lock screen, to allow time for onPause to execute.
    private static final int ON_RESUME_TASKS_DELAY_MSEC = 20;

    private static final String DEBUG_IMAGE_PREFIX = "DEBUG_";

    private CameraActivity mActivity;
    private CameraProxy mCameraDevice;
    private int mCameraId;
    private CameraCapabilities mCameraCapabilities;
    private CameraSettings mCameraSettings;
    private boolean mPaused;

    private PhotoUI mUI;

    // The activity is going to switch to the specified camera id. This is
    // needed because texture copy is done in GL thread. -1 means camera is not
    // switching.
    protected int mPendingSwitchCameraId = -1;

    // When setCameraParametersWhenIdle() is called, we accumulate the subsets
    // needed to be updated in mUpdateSet.
    private int mUpdateSet;

    private float mZoomValue; // The current zoom ratio.
    private int mTimerDuration;
    /** Set when a volume button is clicked to take photo */
    private boolean mVolumeButtonClickedFlag = false;

    private boolean mFocusAreaSupported;
    private boolean mMeteringAreaSupported;
    private boolean mAeLockSupported;
    private boolean mAwbLockSupported;
    private boolean mContinuousFocusSupported;

    // The degrees of the device rotated clockwise from its natural orientation.
    private int mOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;

    private static final String sTempCropFilename = "crop-temp";

    private boolean mFaceDetectionStarted = false;

    // mCropValue and mSaveUri are used only if isImageCaptureIntent() is true.
    private String mCropValue;
    private Uri mSaveUri;

    private Uri mDebugUri;

    // We use a queue to generated names of the images to be used later
    // when the image is ready to be saved.
    private NamedImages mNamedImages;

    private final Runnable mDoSnapRunnable = new Runnable() {
        @Override
        public void run() {
            onShutterButtonClick();
        }
    };

    /**
     * An unpublished intent flag requesting to return as soon as capturing is
     * completed. TODO: consider publishing by moving into MediaStore.
     */
    private static final String EXTRA_QUICK_CAPTURE =
            "android.intent.extra.quickCapture";

    // The display rotation in degrees. This is only valid when mCameraState is
    // not PREVIEW_STOPPED.
    private int mDisplayRotation;
    // The value for android.hardware.Camera.setDisplayOrientation.
    private int mCameraDisplayOrientation;
    // The value for UI components like indicators.
    private int mDisplayOrientation;
    // The value for cameradevice.CameraSettings.setPhotoRotationDegrees.
    private int mJpegRotation;
    // Indicates whether we are using front camera
    private boolean mMirror;
    private boolean mFirstTimeInitialized;
    private boolean mIsImageCaptureIntent;

    private int mCameraState = PREVIEW_STOPPED;
    private boolean mSnapshotOnIdle = false;

    private ContentResolver mContentResolver;

    private AppController mAppController;

    private final PostViewPictureCallback mPostViewPictureCallback =
            new PostViewPictureCallback();
    private final RawPictureCallback mRawPictureCallback =
            new RawPictureCallback();
    private final AutoFocusCallback mAutoFocusCallback =
            new AutoFocusCallback();
    private final Object mAutoFocusMoveCallback =
            ApiHelper.HAS_AUTO_FOCUS_MOVE_CALLBACK
                    ? new AutoFocusMoveCallback()
                    : null;

    private final CameraErrorCallback mErrorCallback = new CameraErrorCallback();

    private long mFocusStartTime;
    private long mShutterCallbackTime;
    private long mPostViewPictureCallbackTime;
    private long mRawPictureCallbackTime;
    private long mJpegPictureCallbackTime;
    private long mOnResumeTime;
    private byte[] mJpegImageData;
    /** Touch coordinate for shutter button press. */
    private TouchCoordinate mShutterTouchCoordinate;


    // These latency time are for the CameraLatency test.
    public long mAutoFocusTime;
    public long mShutterLag;
    public long mShutterToPictureDisplayedTime;
    public long mPictureDisplayedToJpegCallbackTime;
    public long mJpegCallbackFinishTime;
    public long mCaptureStartTime;

    // This handles everything about focus.
    private FocusOverlayManager mFocusManager;

    private final int mGcamModeIndex;
    private SoundPlayer mCountdownSoundPlayer;

    private CameraCapabilities.SceneMode mSceneMode;

    private final Handler mHandler = new MainHandler(this);

    private boolean mQuickCapture;
    private SensorManager mSensorManager;
    private final float[] mGData = new float[3];
    private final float[] mMData = new float[3];
    private final float[] mR = new float[16];
    private int mHeading = -1;

    /** True if all the parameters needed to start preview is ready. */
    private boolean mCameraPreviewParamsReady = false;

    private final MediaSaver.OnMediaSavedListener mOnMediaSavedListener =
            new MediaSaver.OnMediaSavedListener() {
                @Override
                public void onMediaSaved(Uri uri) {
                    if (uri != null) {
                        mActivity.notifyNewMedia(uri);
                    }
                }
            };
    private boolean mShouldResizeTo16x9 = false;

    private final Runnable mResumeTaskRunnable = new Runnable() {
        @Override
        public void run() {
            onResumeTasks();
        }
    };

    /**
     * We keep the flash setting before entering scene modes (HDR)
     * and restore it after HDR is off.
     */
    private String mFlashModeBeforeSceneMode;

    /**
     * This callback gets called when user select whether or not to
     * turn on geo-tagging.
     */
    public interface LocationDialogCallback {
        /**
         * Gets called after user selected/unselected geo-tagging feature.
         *
         * @param selected whether or not geo-tagging feature is selected
         */
        public void onLocationTaggingSelected(boolean selected);
    }

    /**
     * This callback defines the text that is shown in the aspect ratio selection
     * dialog, provides the current aspect ratio, and gets notified when user changes
     * aspect ratio selection in the dialog.
     */
    public interface AspectRatioDialogCallback {
        /**
         * Returns current aspect ratio that is being used to set as default.
         */
        public AspectRatioSelector.AspectRatio getCurrentAspectRatio();

        /**
         * Gets notified when user has made the aspect ratio selection.
         *
         * @param newAspectRatio aspect ratio that user has selected
         * @param dialogHandlingFinishedRunnable runnable to run when the operations
         *                                       needed to handle changes from dialog
         *                                       are finished.
         */
        public void onAspectRatioSelected(AspectRatioSelector.AspectRatio newAspectRatio,
                Runnable dialogHandlingFinishedRunnable);
    }

    private void checkDisplayRotation() {
        // Set the display orientation if display rotation has changed.
        // Sometimes this happens when the device is held upside
        // down and camera app is opened. Rotation animation will
        // take some time and the rotation value we have got may be
        // wrong. Framework does not have a callback for this now.
        if (CameraUtil.getDisplayRotation(mActivity) != mDisplayRotation) {
            setDisplayOrientation();
        }
        if (SystemClock.uptimeMillis() - mOnResumeTime < 5000) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    checkDisplayRotation();
                }
            }, 100);
        }
    }

    /**
     * This Handler is used to post message back onto the main thread of the
     * application
     */
    private static class MainHandler extends Handler {
        private final WeakReference<PhotoModule> mModule;

        public MainHandler(PhotoModule module) {
            super(Looper.getMainLooper());
            mModule = new WeakReference<PhotoModule>(module);
        }

        @Override
        public void handleMessage(Message msg) {
            PhotoModule module = mModule.get();
            if (module == null) {
                return;
            }
            switch (msg.what) {
                case MSG_FIRST_TIME_INIT: {
                    module.initializeFirstTime();
                    break;
                }

                case MSG_SET_CAMERA_PARAMETERS_WHEN_IDLE: {
                    module.setCameraParametersWhenIdle(0);
                    break;
                }
            }
        }
    }

    private void switchToGcamCapture() {
        if (mActivity != null && mGcamModeIndex != 0) {
            SettingsManager settingsManager = mActivity.getSettingsManager();
            settingsManager.set(SettingsManager.SCOPE_GLOBAL,
                                Keys.KEY_CAMERA_HDR_PLUS, true);

            // Disable the HDR+ button to prevent callbacks from being
            // queued before the correct callback is attached to the button
            // in the new module.  The new module will set the enabled/disabled
            // of this button when the module's preferred camera becomes available.
            ButtonManager buttonManager = mActivity.getButtonManager();

            buttonManager.disableButtonClick(ButtonManager.BUTTON_HDR_PLUS);

            mAppController.getCameraAppUI().freezeScreenUntilPreviewReady();

            // Do not post this to avoid this module switch getting interleaved with
            // other button callbacks.
            mActivity.onModeSelected(mGcamModeIndex);

            buttonManager.enableButtonClick(ButtonManager.BUTTON_HDR_PLUS);
        }
    }

    /**
     * Constructs a new photo module.
     */
    public PhotoModule(AppController app) {
        super(app);
        mGcamModeIndex = app.getAndroidContext().getResources()
                .getInteger(R.integer.camera_mode_gcam);
    }

    @Override
    public String getPeekAccessibilityString() {
        return mAppController.getAndroidContext()
            .getResources().getString(R.string.photo_accessibility_peek);
    }

    @Override
    public String getModuleStringIdentifier() {
        return PHOTO_MODULE_STRING_ID;
    }

    @Override
    public void init(CameraActivity activity, boolean isSecureCamera, boolean isCaptureIntent) {
        mActivity = activity;
        // TODO: Need to look at the controller interface to see if we can get
        // rid of passing in the activity directly.
        mAppController = mActivity;

        mUI = new PhotoUI(mActivity, this, mActivity.getModuleLayoutRoot());
        mActivity.setPreviewStatusListener(mUI);

        SettingsManager settingsManager = mActivity.getSettingsManager();
        mCameraId = settingsManager.getInteger(mAppController.getModuleScope(),
                                               Keys.KEY_CAMERA_ID);

        // TODO: Move this to SettingsManager as a part of upgrade procedure.
        if (!settingsManager.getBoolean(SettingsManager.SCOPE_GLOBAL,
                                        Keys.KEY_USER_SELECTED_ASPECT_RATIO)) {
            // Switch to back camera to set aspect ratio.
            mCameraId = settingsManager.getIntegerDefault(Keys.KEY_CAMERA_ID);
        }

        mContentResolver = mActivity.getContentResolver();

        // Surface texture is from camera screen nail and startPreview needs it.
        // This must be done before startPreview.
        mIsImageCaptureIntent = isImageCaptureIntent();

        mQuickCapture = mActivity.getIntent().getBooleanExtra(EXTRA_QUICK_CAPTURE, false);
        mSensorManager = (SensorManager) (mActivity.getSystemService(Context.SENSOR_SERVICE));
        mUI.setCountdownFinishedListener(this);
        mCountdownSoundPlayer = new SoundPlayer(mAppController.getAndroidContext());

        // TODO: Make this a part of app controller API.
        View cancelButton = mActivity.findViewById(R.id.shutter_cancel_button);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                cancelCountDown();
            }
        });
    }

    private void cancelCountDown() {
        if (mUI.isCountingDown()) {
            // Cancel on-going countdown.
            mUI.cancelCountDown();
        }
        mAppController.getCameraAppUI().transitionToCapture();
        mAppController.getCameraAppUI().showModeOptions();
    }

    @Override
    public boolean isUsingBottomBar() {
        return true;
    }

    private void initializeControlByIntent() {
        if (mIsImageCaptureIntent) {
            mActivity.getCameraAppUI().transitionToIntentCaptureLayout();
            setupCaptureParams();
        }
    }

    private void onPreviewStarted() {
        mAppController.onPreviewStarted();
        mAppController.setShutterEnabled(true);
        setCameraState(IDLE);
        startFaceDetection();
        settingsFirstRun();
    }

    /**
     * Prompt the user to pick to record location and choose aspect ratio for the
     * very first run of camera only.
     */
    private void settingsFirstRun() {
        final SettingsManager settingsManager = mActivity.getSettingsManager();

        if (mActivity.isSecureCamera() || isImageCaptureIntent()) {
            return;
        }

        boolean locationPrompt = !settingsManager.isSet(SettingsManager.SCOPE_GLOBAL,
                                                        Keys.KEY_RECORD_LOCATION);
        boolean aspectRatioPrompt = !settingsManager.getBoolean(
            SettingsManager.SCOPE_GLOBAL, Keys.KEY_USER_SELECTED_ASPECT_RATIO);
        if (!locationPrompt && !aspectRatioPrompt) {
            return;
        }

        // Check if the back camera exists
        int backCameraId = mAppController.getCameraProvider().getFirstBackCameraId();
        if (backCameraId == -1) {
            // If there is no back camera, do not show the prompt.
            return;
        }

        if (locationPrompt) {
            // Show both location and aspect ratio selection dialog.
            mUI.showLocationAndAspectRatioDialog(new LocationDialogCallback(){
                @Override
                public void onLocationTaggingSelected(boolean selected) {
                    Keys.setLocation(mActivity.getSettingsManager(), selected,
                                     mActivity.getLocationManager());
                }
            }, createAspectRatioDialogCallback());
        } else {
            // App upgrade. Only show aspect ratio selection.
            boolean wasShown = mUI.showAspectRatioDialog(createAspectRatioDialogCallback());
            if (!wasShown) {
                // If the dialog was not shown, set this flag to true so that we
                // never have to check for it again. It means that we don't need
                // to show the dialog on this device.
                mActivity.getSettingsManager().set(SettingsManager.SCOPE_GLOBAL,
                        Keys.KEY_USER_SELECTED_ASPECT_RATIO, true);
            }
        }
    }

    private AspectRatioDialogCallback createAspectRatioDialogCallback() {
        Size currentSize = mCameraSettings.getCurrentPhotoSize();
        float aspectRatio = (float) currentSize.width() / (float) currentSize.height();
        if (aspectRatio < 1f) {
            aspectRatio = 1 / aspectRatio;
        }
        final AspectRatioSelector.AspectRatio currentAspectRatio;
        if (Math.abs(aspectRatio - 4f / 3f) <= 0.1f) {
            currentAspectRatio = AspectRatioSelector.AspectRatio.ASPECT_RATIO_4x3;
        } else if (Math.abs(aspectRatio - 16f / 9f) <= 0.1f) {
            currentAspectRatio = AspectRatioSelector.AspectRatio.ASPECT_RATIO_16x9;
        } else {
            // TODO: Log error and not show dialog.
            return null;
        }

        List<Size> sizes = mCameraCapabilities.getSupportedPhotoSizes();
        List<Size> pictureSizes = ResolutionUtil
                .getDisplayableSizesFromSupported(sizes, true);

        // This logic below finds the largest resolution for each aspect ratio.
        // TODO: Move this somewhere that can be shared with SettingsActivity
        int aspectRatio4x3Resolution = 0;
        int aspectRatio16x9Resolution = 0;
        Size largestSize4x3 = new Size(0, 0);
        Size largestSize16x9 = new Size(0, 0);
        for (Size size : pictureSizes) {
            float pictureAspectRatio = (float) size.width() / (float) size.height();
            pictureAspectRatio = pictureAspectRatio < 1 ?
                    1f / pictureAspectRatio : pictureAspectRatio;
            int resolution = size.width() * size.height();
            if (Math.abs(pictureAspectRatio - 4f / 3f) < 0.1f) {
                if (resolution > aspectRatio4x3Resolution) {
                    aspectRatio4x3Resolution = resolution;
                    largestSize4x3 = size;
                }
            } else if (Math.abs(pictureAspectRatio - 16f / 9f) < 0.1f) {
                if (resolution > aspectRatio16x9Resolution) {
                    aspectRatio16x9Resolution = resolution;
                    largestSize16x9 = size;
                }
            }
        }

        // Use the largest 4x3 and 16x9 sizes as candidates for picture size selection.
        final Size size4x3ToSelect = largestSize4x3;
        final Size size16x9ToSelect = largestSize16x9;

        AspectRatioDialogCallback callback = new AspectRatioDialogCallback() {

            @Override
            public AspectRatioSelector.AspectRatio getCurrentAspectRatio() {
                return currentAspectRatio;
            }

            @Override
            public void onAspectRatioSelected(AspectRatioSelector.AspectRatio newAspectRatio,
                    Runnable dialogHandlingFinishedRunnable) {
                if (newAspectRatio == AspectRatioSelector.AspectRatio.ASPECT_RATIO_4x3) {
                    String largestSize4x3Text = SettingsUtil.sizeToSetting(size4x3ToSelect);
                    mActivity.getSettingsManager().set(SettingsManager.SCOPE_GLOBAL,
                                                       Keys.KEY_PICTURE_SIZE_BACK,
                                                       largestSize4x3Text);
                } else if (newAspectRatio == AspectRatioSelector.AspectRatio.ASPECT_RATIO_16x9) {
                    String largestSize16x9Text = SettingsUtil.sizeToSetting(size16x9ToSelect);
                    mActivity.getSettingsManager().set(SettingsManager.SCOPE_GLOBAL,
                                                       Keys.KEY_PICTURE_SIZE_BACK,
                                                       largestSize16x9Text);
                }
                mActivity.getSettingsManager().set(SettingsManager.SCOPE_GLOBAL,
                                                   Keys.KEY_USER_SELECTED_ASPECT_RATIO, true);
                String aspectRatio = mActivity.getSettingsManager().getString(
                    SettingsManager.SCOPE_GLOBAL,
                    Keys.KEY_USER_SELECTED_ASPECT_RATIO);
                Log.e(TAG, "aspect ratio after setting it to true=" + aspectRatio);
                if (newAspectRatio != currentAspectRatio) {
                    Log.i(TAG, "changing aspect ratio from dialog");
                    stopPreview();
                    startPreview();
                    mUI.setRunnableForNextFrame(dialogHandlingFinishedRunnable);
                } else {
                    mHandler.post(dialogHandlingFinishedRunnable);
                }
            }
        };
        return callback;
    }

    @Override
    public void onPreviewUIReady() {
        Log.i(TAG, "onPreviewUIReady");
        startPreview();
    }

    @Override
    public void onPreviewUIDestroyed() {
        if (mCameraDevice == null) {
            return;
        }
        mCameraDevice.setPreviewTexture(null);
        stopPreview();
    }

    @Override
    public void startPreCaptureAnimation() {
        mAppController.startPreCaptureAnimation();
    }

    private void onCameraOpened() {
        openCameraCommon();
        initializeControlByIntent();
    }

    private void switchCamera() {
        if (mPaused) {
            return;
        }
        cancelCountDown();

        mAppController.freezeScreenUntilPreviewReady();
        SettingsManager settingsManager = mActivity.getSettingsManager();

        Log.i(TAG, "Start to switch camera. id=" + mPendingSwitchCameraId);
        closeCamera();
        mCameraId = mPendingSwitchCameraId;

        settingsManager.set(mAppController.getModuleScope(), Keys.KEY_CAMERA_ID, mCameraId);
        requestCameraOpen();
        mUI.clearFaces();
        if (mFocusManager != null) {
            mFocusManager.removeMessages();
        }

        mMirror = isCameraFrontFacing();
        mFocusManager.setMirror(mMirror);
        // Start switch camera animation. Post a message because
        // onFrameAvailable from the old camera may already exist.
    }

    /**
     * Uses the {@link CameraProvider} to open the currently-selected camera
     * device, using {@link GservicesHelper} to choose between API-1 and API-2.
     */
    private void requestCameraOpen() {
        Log.v(TAG, "requestCameraOpen");
        mActivity.getCameraProvider().requestCamera(mCameraId,
                GservicesHelper.useCamera2ApiThroughPortabilityLayer(mActivity));
    }

    private final ButtonManager.ButtonCallback mCameraCallback =
            new ButtonManager.ButtonCallback() {
                @Override
                public void onStateChanged(int state) {
                    // At the time this callback is fired, the camera id
                    // has be set to the desired camera.

                    if (mPaused || mAppController.getCameraProvider().waitingForCamera()) {
                        return;
                    }
                    // If switching to back camera, and HDR+ is still on,
                    // switch back to gcam, otherwise handle callback normally.
                    SettingsManager settingsManager = mActivity.getSettingsManager();
                    if (Keys.isCameraBackFacing(settingsManager,
                                                mAppController.getModuleScope())) {
                        if (Keys.requestsReturnToHdrPlus(settingsManager,
                                                         mAppController.getModuleScope())) {
                            switchToGcamCapture();
                            return;
                        }
                    }

                    mPendingSwitchCameraId = state;

                    Log.d(TAG, "Start to switch camera. cameraId=" + state);
                    // We need to keep a preview frame for the animation before
                    // releasing the camera. This will trigger
                    // onPreviewTextureCopied.
                    // TODO: Need to animate the camera switch
                    switchCamera();
                }
            };

    private final ButtonManager.ButtonCallback mHdrPlusCallback =
            new ButtonManager.ButtonCallback() {
                @Override
                public void onStateChanged(int state) {
                    SettingsManager settingsManager = mActivity.getSettingsManager();
                    if (GcamHelper.hasGcamAsSeparateModule()) {
                        // Set the camera setting to default backfacing.
                        settingsManager.setToDefault(mAppController.getModuleScope(),
                                                     Keys.KEY_CAMERA_ID);
                        switchToGcamCapture();
                    } else {
                        if (Keys.isHdrOn(settingsManager)) {
                            settingsManager.set(mAppController.getCameraScope(), Keys.KEY_SCENE_MODE,
                                    mCameraCapabilities.getStringifier().stringify(
                                            CameraCapabilities.SceneMode.HDR));
                        } else {
                            settingsManager.set(mAppController.getCameraScope(), Keys.KEY_SCENE_MODE,
                                    mCameraCapabilities.getStringifier().stringify(
                                            CameraCapabilities.SceneMode.AUTO));
                        }
                        updateParametersSceneMode();
                        if (mCameraDevice != null) {
                            mCameraDevice.applySettings(mCameraSettings);
                        }
                        updateSceneMode();
                    }
                }
            };

    private final View.OnClickListener mCancelCallback = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            onCaptureCancelled();
        }
    };

    private final View.OnClickListener mDoneCallback = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            onCaptureDone();
        }
    };

    private final View.OnClickListener mRetakeCallback = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            mActivity.getCameraAppUI().transitionToIntentCaptureLayout();
            onCaptureRetake();
        }
    };

    @Override
    public void hardResetSettings(SettingsManager settingsManager) {
        // PhotoModule should hard reset HDR+ to off,
        // and HDR to off if HDR+ is supported.
        settingsManager.set(SettingsManager.SCOPE_GLOBAL, Keys.KEY_CAMERA_HDR_PLUS, false);
        if (GcamHelper.hasGcamAsSeparateModule()) {
            settingsManager.set(SettingsManager.SCOPE_GLOBAL, Keys.KEY_CAMERA_HDR, false);
        }
    }

    @Override
    public HardwareSpec getHardwareSpec() {
        return (mCameraSettings != null ?
                new HardwareSpecImpl(getCameraProvider(), mCameraCapabilities) : null);
    }

    @Override
    public CameraAppUI.BottomBarUISpec getBottomBarSpec() {
        CameraAppUI.BottomBarUISpec bottomBarSpec = new CameraAppUI.BottomBarUISpec();

        bottomBarSpec.enableCamera = true;
        bottomBarSpec.cameraCallback = mCameraCallback;
        bottomBarSpec.enableFlash = !mAppController.getSettingsManager()
            .getBoolean(SettingsManager.SCOPE_GLOBAL, Keys.KEY_CAMERA_HDR);
        bottomBarSpec.enableHdr = true;
        bottomBarSpec.hdrCallback = mHdrPlusCallback;
        bottomBarSpec.enableGridLines = true;
        if (mCameraCapabilities != null) {
            bottomBarSpec.enableExposureCompensation = true;
            bottomBarSpec.exposureCompensationSetCallback =
                new CameraAppUI.BottomBarUISpec.ExposureCompensationSetCallback() {
                @Override
                public void setExposure(int value) {
                    setExposureCompensation(value);
                }
            };
            bottomBarSpec.minExposureCompensation =
                mCameraCapabilities.getMinExposureCompensation();
            bottomBarSpec.maxExposureCompensation =
                mCameraCapabilities.getMaxExposureCompensation();
            bottomBarSpec.exposureCompensationStep =
                mCameraCapabilities.getExposureCompensationStep();
        }

        bottomBarSpec.enableSelfTimer = true;
        bottomBarSpec.showSelfTimer = true;

        if (isImageCaptureIntent()) {
            bottomBarSpec.showCancel = true;
            bottomBarSpec.cancelCallback = mCancelCallback;
            bottomBarSpec.showDone = true;
            bottomBarSpec.doneCallback = mDoneCallback;
            bottomBarSpec.showRetake = true;
            bottomBarSpec.retakeCallback = mRetakeCallback;
        }

        return bottomBarSpec;
    }

    // either open a new camera or switch cameras
    private void openCameraCommon() {
        mUI.onCameraOpened(mCameraCapabilities, mCameraSettings);
        if (mIsImageCaptureIntent) {
            // Set hdr plus to default: off.
            SettingsManager settingsManager = mActivity.getSettingsManager();
            settingsManager.setToDefault(SettingsManager.SCOPE_GLOBAL,
                                         Keys.KEY_CAMERA_HDR_PLUS);
        }
        updateSceneMode();
    }

    @Override
    public void updatePreviewAspectRatio(float aspectRatio) {
        mAppController.updatePreviewAspectRatio(aspectRatio);
    }

    private void resetExposureCompensation() {
        SettingsManager settingsManager = mActivity.getSettingsManager();
        if (settingsManager == null) {
            Log.e(TAG, "Settings manager is null!");
            return;
        }
        settingsManager.setToDefault(mAppController.getCameraScope(),
                                     Keys.KEY_EXPOSURE);
    }

    // Snapshots can only be taken after this is called. It should be called
    // once only. We could have done these things in onCreate() but we want to
    // make preview screen appear as soon as possible.
    private void initializeFirstTime() {
        if (mFirstTimeInitialized || mPaused) {
            return;
        }

        mUI.initializeFirstTime();

        // We set the listener only when both service and shutterbutton
        // are initialized.
        getServices().getMemoryManager().addListener(this);

        mNamedImages = new NamedImages();

        mFirstTimeInitialized = true;
        addIdleHandler();

        mActivity.updateStorageSpaceAndHint(null);
    }

    // If the activity is paused and resumed, this method will be called in
    // onResume.
    private void initializeSecondTime() {
        getServices().getMemoryManager().addListener(this);
        mNamedImages = new NamedImages();
        mUI.initializeSecondTime(mCameraCapabilities, mCameraSettings);
    }

    private void addIdleHandler() {
        MessageQueue queue = Looper.myQueue();
        queue.addIdleHandler(new MessageQueue.IdleHandler() {
            @Override
            public boolean queueIdle() {
                Storage.ensureOSXCompatible();
                return false;
            }
        });
    }

    @Override
    public void startFaceDetection() {
        if (mFaceDetectionStarted || mCameraDevice == null) {
            return;
        }
        if (mCameraCapabilities.getMaxNumOfFacesSupported() > 0) {
            mFaceDetectionStarted = true;
            mUI.onStartFaceDetection(mDisplayOrientation, isCameraFrontFacing());
            mCameraDevice.setFaceDetectionCallback(mHandler, mUI);
            mCameraDevice.startFaceDetection();
            SessionStatsCollector.instance().faceScanActive(true);
        }
    }

    @Override
    public void stopFaceDetection() {
        if (!mFaceDetectionStarted || mCameraDevice == null) {
            return;
        }
        if (mCameraCapabilities.getMaxNumOfFacesSupported() > 0) {
            mFaceDetectionStarted = false;
            mCameraDevice.setFaceDetectionCallback(null, null);
            mCameraDevice.stopFaceDetection();
            mUI.clearFaces();
            SessionStatsCollector.instance().faceScanActive(false);
        }
    }

    private final class ShutterCallback
            implements CameraShutterCallback {

        private final boolean mNeedsAnimation;

        public ShutterCallback(boolean needsAnimation) {
            mNeedsAnimation = needsAnimation;
        }

        @Override
        public void onShutter(CameraProxy camera) {
            mShutterCallbackTime = System.currentTimeMillis();
            mShutterLag = mShutterCallbackTime - mCaptureStartTime;
            Log.v(TAG, "mShutterLag = " + mShutterLag + "ms");
            if (mNeedsAnimation) {
                mActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        animateAfterShutter();
                    }
                });
            }
        }
    }

    private final class PostViewPictureCallback
            implements CameraPictureCallback {
        @Override
        public void onPictureTaken(byte[] data, CameraProxy camera) {
            mPostViewPictureCallbackTime = System.currentTimeMillis();
            Log.v(TAG, "mShutterToPostViewCallbackTime = "
                    + (mPostViewPictureCallbackTime - mShutterCallbackTime)
                    + "ms");
        }
    }

    private final class RawPictureCallback
            implements CameraPictureCallback {
        @Override
        public void onPictureTaken(byte[] rawData, CameraProxy camera) {
            mRawPictureCallbackTime = System.currentTimeMillis();
            Log.v(TAG, "mShutterToRawCallbackTime = "
                    + (mRawPictureCallbackTime - mShutterCallbackTime) + "ms");
        }
    }

    private static class ResizeBundle {
        byte[] jpegData;
        float targetAspectRatio;
        ExifInterface exif;
    }

    /**
     * @return Cropped image if the target aspect ratio is larger than the jpeg
     *         aspect ratio on the long axis. The original jpeg otherwise.
     */
    private ResizeBundle cropJpegDataToAspectRatio(ResizeBundle dataBundle) {

        final byte[] jpegData = dataBundle.jpegData;
        final ExifInterface exif = dataBundle.exif;
        float targetAspectRatio = dataBundle.targetAspectRatio;

        Bitmap original = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
        int originalWidth = original.getWidth();
        int originalHeight = original.getHeight();
        int newWidth;
        int newHeight;

        if (originalWidth > originalHeight) {
            newHeight = (int) (originalWidth / targetAspectRatio);
            newWidth = originalWidth;
        } else {
            newWidth = (int) (originalHeight / targetAspectRatio);
            newHeight = originalHeight;
        }
        int xOffset = (originalWidth - newWidth)/2;
        int yOffset = (originalHeight - newHeight)/2;

        if (xOffset < 0 || yOffset < 0) {
            return dataBundle;
        }

        Bitmap resized = Bitmap.createBitmap(original,xOffset,yOffset,newWidth, newHeight);
        exif.setTagValue(ExifInterface.TAG_PIXEL_X_DIMENSION, new Integer(newWidth));
        exif.setTagValue(ExifInterface.TAG_PIXEL_Y_DIMENSION, new Integer(newHeight));

        ByteArrayOutputStream stream = new ByteArrayOutputStream();

        resized.compress(Bitmap.CompressFormat.JPEG, 90, stream);
        dataBundle.jpegData = stream.toByteArray();
        return dataBundle;
    }

    private final class JpegPictureCallback
            implements CameraPictureCallback {
        Location mLocation;

        public JpegPictureCallback(Location loc) {
            mLocation = loc;
        }

        @Override
        public void onPictureTaken(final byte[] originalJpegData, final CameraProxy camera) {
            Log.i(TAG, "onPictureTaken");
            mAppController.setShutterEnabled(true);
            if (mPaused) {
                return;
            }
            if (mIsImageCaptureIntent) {
                stopPreview();
            }
            if (mSceneMode == CameraCapabilities.SceneMode.HDR) {
                mUI.setSwipingEnabled(true);
            }

            mJpegPictureCallbackTime = System.currentTimeMillis();
            // If postview callback has arrived, the captured image is displayed
            // in postview callback. If not, the captured image is displayed in
            // raw picture callback.
            if (mPostViewPictureCallbackTime != 0) {
                mShutterToPictureDisplayedTime =
                        mPostViewPictureCallbackTime - mShutterCallbackTime;
                mPictureDisplayedToJpegCallbackTime =
                        mJpegPictureCallbackTime - mPostViewPictureCallbackTime;
            } else {
                mShutterToPictureDisplayedTime =
                        mRawPictureCallbackTime - mShutterCallbackTime;
                mPictureDisplayedToJpegCallbackTime =
                        mJpegPictureCallbackTime - mRawPictureCallbackTime;
            }
            Log.v(TAG, "mPictureDisplayedToJpegCallbackTime = "
                    + mPictureDisplayedToJpegCallbackTime + "ms");

            mFocusManager.updateFocusUI(); // Ensure focus indicator is hidden.
            if (!mIsImageCaptureIntent) {
                setupPreview();
            }

            long now = System.currentTimeMillis();
            mJpegCallbackFinishTime = now - mJpegPictureCallbackTime;
            Log.v(TAG, "mJpegCallbackFinishTime = " + mJpegCallbackFinishTime + "ms");
            mJpegPictureCallbackTime = 0;

            final ExifInterface exif = Exif.getExif(originalJpegData);

            if (mShouldResizeTo16x9) {
                final ResizeBundle dataBundle = new ResizeBundle();
                dataBundle.jpegData = originalJpegData;
                dataBundle.targetAspectRatio = ResolutionUtil.NEXUS_5_LARGE_16_BY_9_ASPECT_RATIO;
                dataBundle.exif = exif;
                new AsyncTask<ResizeBundle, Void, ResizeBundle>() {

                    @Override
                    protected ResizeBundle doInBackground(ResizeBundle... resizeBundles) {
                        return cropJpegDataToAspectRatio(resizeBundles[0]);
                    }

                    @Override
                    protected void onPostExecute(ResizeBundle result) {
                        saveFinalPhoto(result.jpegData, result.exif, camera);
                    }
                }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, dataBundle);

            } else {
                saveFinalPhoto(originalJpegData, exif, camera);
            }
        }

        void saveFinalPhoto(final byte[] jpegData, final ExifInterface exif, CameraProxy camera) {

            int orientation = Exif.getOrientation(exif);

            float zoomValue = 1.0f;
            if (mCameraCapabilities.supports(CameraCapabilities.Feature.ZOOM)) {
                zoomValue = mCameraSettings.getCurrentZoomRatio();
            }
            boolean hdrOn = CameraCapabilities.SceneMode.HDR == mSceneMode;
            String flashSetting =
                    mActivity.getSettingsManager().getString(mAppController.getCameraScope(),
                                                             Keys.KEY_FLASH_MODE);
            boolean gridLinesOn = Keys.areGridLinesOn(mActivity.getSettingsManager());
            UsageStatistics.instance().photoCaptureDoneEvent(
                    eventprotos.NavigationChange.Mode.PHOTO_CAPTURE,
                    mNamedImages.mQueue.lastElement().title + ".jpg", exif,
                    isCameraFrontFacing(), hdrOn, zoomValue, flashSetting, gridLinesOn,
                    (float) mTimerDuration, mShutterTouchCoordinate, mVolumeButtonClickedFlag);
            mShutterTouchCoordinate = null;
            mVolumeButtonClickedFlag = false;

            if (!mIsImageCaptureIntent) {
                // Calculate the width and the height of the jpeg.
                Integer exifWidth = exif.getTagIntValue(ExifInterface.TAG_PIXEL_X_DIMENSION);
                Integer exifHeight = exif.getTagIntValue(ExifInterface.TAG_PIXEL_Y_DIMENSION);
                int width, height;
                if (mShouldResizeTo16x9 && exifWidth != null && exifHeight != null) {
                    width = exifWidth;
                    height = exifHeight;
                } else {
                    Size s;
                    s = mCameraSettings.getCurrentPhotoSize();
                    if ((mJpegRotation + orientation) % 180 == 0) {
                        width = s.width();
                        height = s.height();
                    } else {
                        width = s.height();
                        height = s.width();
                    }
                }
                NamedEntity name = mNamedImages.getNextNameEntity();
                String title = (name == null) ? null : name.title;
                long date = (name == null) ? -1 : name.date;

                // Handle debug mode outputs
                if (mDebugUri != null) {
                    // If using a debug uri, save jpeg there.
                    saveToDebugUri(jpegData);

                    // Adjust the title of the debug image shown in mediastore.
                    if (title != null) {
                        title = DEBUG_IMAGE_PREFIX + title;
                    }
                }

                if (title == null) {
                    Log.e(TAG, "Unbalanced name/data pair");
                } else {
                    if (date == -1) {
                        date = mCaptureStartTime;
                    }
                    if (mHeading >= 0) {
                        // heading direction has been updated by the sensor.
                        ExifTag directionRefTag = exif.buildTag(
                                ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
                                ExifInterface.GpsTrackRef.MAGNETIC_DIRECTION);
                        ExifTag directionTag = exif.buildTag(
                                ExifInterface.TAG_GPS_IMG_DIRECTION,
                                new Rational(mHeading, 1));
                        exif.setTag(directionRefTag);
                        exif.setTag(directionTag);
                    }
                    getServices().getMediaSaver().addImage(
                            jpegData, title, date, mLocation, width, height,
                            orientation, exif, mOnMediaSavedListener, mContentResolver);
                }
                // Animate capture with real jpeg data instead of a preview
                // frame.
                mUI.animateCapture(jpegData, orientation, mMirror);
            } else {
                mJpegImageData = jpegData;
                if (!mQuickCapture) {
                    mUI.showCapturedImageForReview(jpegData, orientation, mMirror);
                } else {
                    onCaptureDone();
                }
            }

            // Send the taken photo to remote shutter listeners, if any are
            // registered.
            getServices().getRemoteShutterListener().onPictureTaken(jpegData);

            // Check this in advance of each shot so we don't add to shutter
            // latency. It's true that someone else could write to the SD card
            // in the mean time and fill it, but that could have happened
            // between the shutter press and saving the JPEG too.
            mActivity.updateStorageSpaceAndHint(null);
        }
    }

    private final class AutoFocusCallback implements CameraAFCallback {
        @Override
        public void onAutoFocus(boolean focused, CameraProxy camera) {
            SessionStatsCollector.instance().autofocusResult(focused);
            if (mPaused) {
                return;
            }

            mAutoFocusTime = System.currentTimeMillis() - mFocusStartTime;
            Log.v(TAG, "mAutoFocusTime = " + mAutoFocusTime + "ms   focused = "+focused);
            setCameraState(IDLE);
            mFocusManager.onAutoFocus(focused, false);
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private final class AutoFocusMoveCallback
            implements CameraAFMoveCallback {
        @Override
        public void onAutoFocusMoving(
                boolean moving, CameraProxy camera) {
            mFocusManager.onAutoFocusMoving(moving);
            SessionStatsCollector.instance().autofocusMoving(moving);
        }
    }

    /**
     * This class is just a thread-safe queue for name,date holder objects.
     */
    public static class NamedImages {
        private final Vector<NamedEntity> mQueue;

        public NamedImages() {
            mQueue = new Vector<NamedEntity>();
        }

        public void nameNewImage(long date) {
            NamedEntity r = new NamedEntity();
            r.title = CameraUtil.createJpegName(date);
            r.date = date;
            mQueue.add(r);
        }

        public NamedEntity getNextNameEntity() {
            synchronized (mQueue) {
                if (!mQueue.isEmpty()) {
                    return mQueue.remove(0);
                }
            }
            return null;
        }

        public static class NamedEntity {
            public String title;
            public long date;
        }
    }

    private void setCameraState(int state) {
        mCameraState = state;
        switch (state) {
            case PREVIEW_STOPPED:
            case SNAPSHOT_IN_PROGRESS:
            case SWITCHING_CAMERA:
                // TODO: Tell app UI to disable swipe
                break;
            case PhotoController.IDLE:
                // TODO: Tell app UI to enable swipe
                break;
        }
    }

    private void animateAfterShutter() {
        // Only animate when in full screen capture mode
        // i.e. If monkey/a user swipes to the gallery during picture taking,
        // don't show animation
        if (!mIsImageCaptureIntent) {
            mUI.animateFlash();
        }
    }

    @Override
    public boolean capture() {
        // If we are already in the middle of taking a snapshot or the image
        // save request is full then ignore.
        if (mCameraDevice == null || mCameraState == SNAPSHOT_IN_PROGRESS
                || mCameraState == SWITCHING_CAMERA || !mAppController.isShutterEnabled()) {
            return false;
        }
        mCaptureStartTime = System.currentTimeMillis();

        mPostViewPictureCallbackTime = 0;
        mJpegImageData = null;

        final boolean animateBefore = (mSceneMode == CameraCapabilities.SceneMode.HDR);

        if (animateBefore) {
            animateAfterShutter();
        }

        Location loc = mActivity.getLocationManager().getCurrentLocation();
        CameraUtil.setGpsParameters(mCameraSettings, loc);
        mCameraDevice.applySettings(mCameraSettings);

        // Set JPEG orientation. Even if screen UI is locked in portrait, camera orientation should
        // still match device orientation (e.g., users should always get landscape photos while
        // capturing by putting device in landscape.)
        int orientation = mActivity.isAutoRotateScreen() ? mDisplayRotation : mOrientation;
        Characteristics info = mActivity.getCameraProvider().getCharacteristics(mCameraId);
        mJpegRotation = info.getJpegOrientation(orientation);
        mCameraDevice.setJpegOrientation(mJpegRotation);

        // We don't want user to press the button again while taking a
        // multi-second HDR photo.
        mAppController.setShutterEnabled(false);
        mCameraDevice.takePicture(mHandler,
                new ShutterCallback(!animateBefore),
                mRawPictureCallback, mPostViewPictureCallback,
                new JpegPictureCallback(loc));

        mNamedImages.nameNewImage(mCaptureStartTime);

        mFaceDetectionStarted = false;
        setCameraState(SNAPSHOT_IN_PROGRESS);
        return true;
    }

    @Override
    public void setFocusParameters() {
        setCameraParameters(UPDATE_PARAM_PREFERENCE);
    }

    private void updateSceneMode() {
        // If scene mode is set, we cannot set flash mode, white balance, and
        // focus mode, instead, we read it from driver
        if (CameraCapabilities.SceneMode.AUTO != mSceneMode) {
            overrideCameraSettings(mCameraSettings.getCurrentFlashMode(),
                    mCameraSettings.getCurrentFocusMode());
        }
    }

    private void overrideCameraSettings(CameraCapabilities.FlashMode flashMode,
            CameraCapabilities.FocusMode focusMode) {
        CameraCapabilities.Stringifier stringifier = mCameraCapabilities.getStringifier();
        SettingsManager settingsManager = mActivity.getSettingsManager();
        if (!CameraCapabilities.FlashMode.NO_FLASH.equals(flashMode)) {
            settingsManager.set(mAppController.getCameraScope(), Keys.KEY_FLASH_MODE,
                    stringifier.stringify(flashMode));
        }
        settingsManager.set(mAppController.getCameraScope(), Keys.KEY_FOCUS_MODE,
                stringifier.stringify(focusMode));
    }

    @Override
    public void onOrientationChanged(int orientation) {
        if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
            return;
        }

        // TODO: Document orientation compute logic and unify them in OrientationManagerImpl.
        // b/17443789
        // Flip to counter-clockwise orientation.
        mOrientation = (360 - orientation) % 360;
    }

    @Override
    public void onCameraAvailable(CameraProxy cameraProxy) {
        Log.i(TAG, "onCameraAvailable");
        if (mPaused) {
            return;
        }
        mCameraDevice = cameraProxy;

        initializeCapabilities();

        // Reset zoom value index.
        mZoomValue = 1.0f;
        if (mFocusManager == null) {
            initializeFocusManager();
        }
        mFocusManager.updateCapabilities(mCameraCapabilities);

        // Do camera parameter dependent initialization.
        mCameraSettings = mCameraDevice.getSettings();

        setCameraParameters(UPDATE_PARAM_ALL);
        // Set a listener which updates camera parameters based
        // on changed settings.
        SettingsManager settingsManager = mActivity.getSettingsManager();
        settingsManager.addListener(this);
        mCameraPreviewParamsReady = true;

        startPreview();

        onCameraOpened();
    }

    @Override
    public void onCaptureCancelled() {
        mActivity.setResultEx(Activity.RESULT_CANCELED, new Intent());
        mActivity.finish();
    }

    @Override
    public void onCaptureRetake() {
        Log.i(TAG, "onCaptureRetake");
        if (mPaused) {
            return;
        }
        mUI.hidePostCaptureAlert();
        mUI.hideIntentReviewImageView();
        setupPreview();
    }

    @Override
    public void onCaptureDone() {
        if (mPaused) {
            return;
        }

        byte[] data = mJpegImageData;

        if (mCropValue == null) {
            // First handle the no crop case -- just return the value. If the
            // caller specifies a "save uri" then write the data to its
            // stream. Otherwise, pass back a scaled down version of the bitmap
            // directly in the extras.
            if (mSaveUri != null) {
                OutputStream outputStream = null;
                try {
                    outputStream = mContentResolver.openOutputStream(mSaveUri);
                    outputStream.write(data);
                    outputStream.close();

                    Log.v(TAG, "saved result to URI: " + mSaveUri);
                    mActivity.setResultEx(Activity.RESULT_OK);
                    mActivity.finish();
                } catch (IOException ex) {
                    Log.w(TAG, "exception saving result to URI: " + mSaveUri, ex);
                    // ignore exception
                } finally {
                    CameraUtil.closeSilently(outputStream);
                }
            } else {
                ExifInterface exif = Exif.getExif(data);
                int orientation = Exif.getOrientation(exif);
                Bitmap bitmap = CameraUtil.makeBitmap(data, 50 * 1024);
                bitmap = CameraUtil.rotate(bitmap, orientation);
                Log.v(TAG, "inlined bitmap into capture intent result");
                mActivity.setResultEx(Activity.RESULT_OK,
                        new Intent("inline-data").putExtra("data", bitmap));
                mActivity.finish();
            }
        } else {
            // Save the image to a temp file and invoke the cropper
            Uri tempUri = null;
            FileOutputStream tempStream = null;
            try {
                File path = mActivity.getFileStreamPath(sTempCropFilename);
                path.delete();
                tempStream = mActivity.openFileOutput(sTempCropFilename, 0);
                tempStream.write(data);
                tempStream.close();
                tempUri = Uri.fromFile(path);
                Log.v(TAG, "wrote temp file for cropping to: " + sTempCropFilename);
            } catch (FileNotFoundException ex) {
                Log.w(TAG, "error writing temp cropping file to: " + sTempCropFilename, ex);
                mActivity.setResultEx(Activity.RESULT_CANCELED);
                mActivity.finish();
                return;
            } catch (IOException ex) {
                Log.w(TAG, "error writing temp cropping file to: " + sTempCropFilename, ex);
                mActivity.setResultEx(Activity.RESULT_CANCELED);
                mActivity.finish();
                return;
            } finally {
                CameraUtil.closeSilently(tempStream);
            }

            Bundle newExtras = new Bundle();
            if (mCropValue.equals("circle")) {
                newExtras.putString("circleCrop", "true");
            }
            if (mSaveUri != null) {
                Log.v(TAG, "setting output of cropped file to: " + mSaveUri);
                newExtras.putParcelable(MediaStore.EXTRA_OUTPUT, mSaveUri);
            } else {
                newExtras.putBoolean(CameraUtil.KEY_RETURN_DATA, true);
            }
            if (mActivity.isSecureCamera()) {
                newExtras.putBoolean(CameraUtil.KEY_SHOW_WHEN_LOCKED, true);
            }

            // TODO: Share this constant.
            final String CROP_ACTION = "com.android.camera.action.CROP";
            Intent cropIntent = new Intent(CROP_ACTION);

            cropIntent.setData(tempUri);
            cropIntent.putExtras(newExtras);
            Log.v(TAG, "starting CROP intent for capture");
            mActivity.startActivityForResult(cropIntent, REQUEST_CROP);
        }
    }

    @Override
    public void onShutterCoordinate(TouchCoordinate coord) {
        mShutterTouchCoordinate = coord;
    }

    @Override
    public void onShutterButtonFocus(boolean pressed) {
        // Do nothing. We don't support half-press to focus anymore.
    }

    @Override
    public void onShutterButtonClick() {
        if (mPaused || (mCameraState == SWITCHING_CAMERA)
                || (mCameraState == PREVIEW_STOPPED)) {
            mVolumeButtonClickedFlag = false;
            return;
        }

        // Do not take the picture if there is not enough storage.
        if (mActivity.getStorageSpaceBytes() <= Storage.LOW_STORAGE_THRESHOLD_BYTES) {
            Log.i(TAG, "Not enough space or storage not ready. remaining="
                    + mActivity.getStorageSpaceBytes());
            mVolumeButtonClickedFlag = false;
            return;
        }
        Log.d(TAG, "onShutterButtonClick: mCameraState=" + mCameraState +
                " mVolumeButtonClickedFlag=" + mVolumeButtonClickedFlag);

        int countDownDuration = mActivity.getSettingsManager()
            .getInteger(SettingsManager.SCOPE_GLOBAL, Keys.KEY_COUNTDOWN_DURATION);
        mTimerDuration = countDownDuration;
        if (countDownDuration > 0) {
            // Start count down.
            mAppController.getCameraAppUI().transitionToCancel();
            mAppController.getCameraAppUI().hideModeOptions();
            mUI.startCountdown(countDownDuration);
            return;
        } else {
            focusAndCapture();
        }
    }

    private void focusAndCapture() {
        if (mSceneMode == CameraCapabilities.SceneMode.HDR) {
            mUI.setSwipingEnabled(false);
        }
        // If the user wants to do a snapshot while the previous one is still
        // in progress, remember the fact and do it after we finish the previous
        // one and re-start the preview. Snapshot in progress also includes the
        // state that autofocus is focusing and a picture will be taken when
        // focus callback arrives.
        if ((mFocusManager.isFocusingSnapOnFinish() || mCameraState == SNAPSHOT_IN_PROGRESS)) {
            if (!mIsImageCaptureIntent) {
                mSnapshotOnIdle = true;
            }
            return;
        }

        mSnapshotOnIdle = false;
        mFocusManager.focusAndCapture(mCameraSettings.getCurrentFocusMode());
    }

    @Override
    public void onRemainingSecondsChanged(int remainingSeconds) {
        if (remainingSeconds == 1) {
            mCountdownSoundPlayer.play(R.raw.timer_final_second, 0.6f);
        } else if (remainingSeconds == 2 || remainingSeconds == 3) {
            mCountdownSoundPlayer.play(R.raw.timer_increment, 0.6f);
        }
    }

    @Override
    public void onCountDownFinished() {
        if (mIsImageCaptureIntent) {
            mAppController.getCameraAppUI().transitionToIntentReviewLayout();
        } else {
            mAppController.getCameraAppUI().transitionToCapture();
        }
        mAppController.getCameraAppUI().showModeOptions();
        if (mPaused) {
            return;
        }
        focusAndCapture();
    }

    private void onResumeTasks() {
        if (mPaused) {
            return;
        }
        Log.v(TAG, "Executing onResumeTasks.");

        mCountdownSoundPlayer.loadSound(R.raw.timer_final_second);
        mCountdownSoundPlayer.loadSound(R.raw.timer_increment);
        if (mFocusManager != null) {
            // If camera is not open when resume is called, focus manager will
            // not be initialized yet, in which case it will start listening to
            // preview area size change later in the initialization.
            mAppController.addPreviewAreaSizeChangedListener(mFocusManager);
        }
        mAppController.addPreviewAreaSizeChangedListener(mUI);

        CameraProvider camProvider = mActivity.getCameraProvider();
        if (camProvider == null) {
            // No camera provider, the Activity is destroyed already.
            return;
        }
        requestCameraOpen();

        mJpegPictureCallbackTime = 0;
        mZoomValue = 1.0f;

        mOnResumeTime = SystemClock.uptimeMillis();
        checkDisplayRotation();

        // If first time initialization is not finished, put it in the
        // message queue.
        if (!mFirstTimeInitialized) {
            mHandler.sendEmptyMessage(MSG_FIRST_TIME_INIT);
        } else {
            initializeSecondTime();
        }

        Sensor gsensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (gsensor != null) {
            mSensorManager.registerListener(this, gsensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        Sensor msensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (msensor != null) {
            mSensorManager.registerListener(this, msensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        getServices().getRemoteShutterListener().onModuleReady(this);
        SessionStatsCollector.instance().sessionActive(true);
    }

    /**
     * @return Whether the currently active camera is front-facing.
     */
    private boolean isCameraFrontFacing() {
        return mAppController.getCameraProvider().getCharacteristics(mCameraId)
                .isFacingFront();
    }

    /**
     * The focus manager is the first UI related element to get initialized, and
     * it requires the RenderOverlay, so initialize it here
     */
    private void initializeFocusManager() {
        // Create FocusManager object. startPreview needs it.
        // if mFocusManager not null, reuse it
        // otherwise create a new instance
        if (mFocusManager != null) {
            mFocusManager.removeMessages();
        } else {
            mMirror = isCameraFrontFacing();
            String[] defaultFocusModesStrings = mActivity.getResources().getStringArray(
                    R.array.pref_camera_focusmode_default_array);
            ArrayList<CameraCapabilities.FocusMode> defaultFocusModes =
                    new ArrayList<CameraCapabilities.FocusMode>();
            CameraCapabilities.Stringifier stringifier = mCameraCapabilities.getStringifier();
            for (String modeString : defaultFocusModesStrings) {
                CameraCapabilities.FocusMode mode = stringifier.focusModeFromString(modeString);
                if (mode != null) {
                    defaultFocusModes.add(mode);
                }
            }
            mFocusManager =
                    new FocusOverlayManager(mAppController, defaultFocusModes,
                            mCameraCapabilities, this, mMirror, mActivity.getMainLooper(),
                            mUI.getFocusUI());
            MotionManager motionManager = getServices().getMotionManager();
            if (motionManager != null) {
                motionManager.addListener(mFocusManager);
            }
        }
        mAppController.addPreviewAreaSizeChangedListener(mFocusManager);
    }

    /**
     * @return Whether we are resuming from within the lockscreen.
     */
    private boolean isResumeFromLockscreen() {
        String action = mActivity.getIntent().getAction();
        return (MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA.equals(action)
                || MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE.equals(action));
    }

    @Override
    public void resume() {
        mPaused = false;

        // Add delay on resume from lock screen only, in order to to speed up
        // the onResume --> onPause --> onResume cycle from lock screen.
        // Don't do always because letting go of thread can cause delay.
        if (isResumeFromLockscreen()) {
            Log.v(TAG, "On resume, from lock screen.");
            // Note: onPauseAfterSuper() will delete this runnable, so we will
            // at most have 1 copy queued up.
            mHandler.postDelayed(mResumeTaskRunnable, ON_RESUME_TASKS_DELAY_MSEC);
        } else {
            Log.v(TAG, "On resume.");
            onResumeTasks();
        }
    }

    @Override
    public void pause() {
        mPaused = true;
        mHandler.removeCallbacks(mResumeTaskRunnable);
        getServices().getRemoteShutterListener().onModuleExit();
        SessionStatsCollector.instance().sessionActive(false);

        Sensor gsensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (gsensor != null) {
            mSensorManager.unregisterListener(this, gsensor);
        }

        Sensor msensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (msensor != null) {
            mSensorManager.unregisterListener(this, msensor);
        }

        // Reset the focus first. Camera CTS does not guarantee that
        // cancelAutoFocus is allowed after preview stops.
        if (mCameraDevice != null && mCameraState != PREVIEW_STOPPED) {
            mCameraDevice.cancelAutoFocus();
        }

        // If the camera has not been opened asynchronously yet,
        // and startPreview hasn't been called, then this is a no-op.
        // (e.g. onResume -> onPause -> onResume).
        stopPreview();
        cancelCountDown();
        mCountdownSoundPlayer.release();

        mNamedImages = null;
        // If we are in an image capture intent and has taken
        // a picture, we just clear it in onPause.
        mJpegImageData = null;

        // Remove the messages and runnables in the queue.
        mHandler.removeCallbacksAndMessages(null);

        closeCamera();
        mActivity.enableKeepScreenOn(false);
        mUI.onPause();

        mPendingSwitchCameraId = -1;
        if (mFocusManager != null) {
            mFocusManager.removeMessages();
        }
        getServices().getMemoryManager().removeListener(this);
        mAppController.removePreviewAreaSizeChangedListener(mFocusManager);
        mAppController.removePreviewAreaSizeChangedListener(mUI);

        SettingsManager settingsManager = mActivity.getSettingsManager();
        settingsManager.removeListener(this);
    }

    @Override
    public void destroy() {
        // TODO: implement this.
    }

    @Override
    public void onLayoutOrientationChanged(boolean isLandscape) {
        setDisplayOrientation();
    }

    @Override
    public void updateCameraOrientation() {
        if (mDisplayRotation != CameraUtil.getDisplayRotation(mActivity)) {
            setDisplayOrientation();
        }
    }

    private boolean canTakePicture() {
        return isCameraIdle()
                && (mActivity.getStorageSpaceBytes() > Storage.LOW_STORAGE_THRESHOLD_BYTES);
    }

    @Override
    public void autoFocus() {
        if (mCameraDevice == null) {
            return;
        }
        Log.v(TAG,"Starting auto focus");
        mFocusStartTime = System.currentTimeMillis();
        mCameraDevice.autoFocus(mHandler, mAutoFocusCallback);
        SessionStatsCollector.instance().autofocusManualTrigger();
        setCameraState(FOCUSING);
    }

    @Override
    public void cancelAutoFocus() {
        if (mCameraDevice == null) {
            return;
        }
        mCameraDevice.cancelAutoFocus();
        setCameraState(IDLE);
        setCameraParameters(UPDATE_PARAM_PREFERENCE);
    }

    @Override
    public void onSingleTapUp(View view, int x, int y) {
        if (mPaused || mCameraDevice == null || !mFirstTimeInitialized
                || mCameraState == SNAPSHOT_IN_PROGRESS
                || mCameraState == SWITCHING_CAMERA
                || mCameraState == PREVIEW_STOPPED) {
            return;
        }

        // Check if metering area or focus area is supported.
        if (!mFocusAreaSupported && !mMeteringAreaSupported) {
            return;
        }
        mFocusManager.onSingleTapUp(x, y);
    }

    @Override
    public boolean onBackPressed() {
        return mUI.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_FOCUS:
                if (/* TODO: mActivity.isInCameraApp() && */mFirstTimeInitialized &&
                    !mActivity.getCameraAppUI().isInIntentReview()) {
                    if (event.getRepeatCount() == 0) {
                        onShutterButtonFocus(true);
                    }
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_CAMERA:
                if (mFirstTimeInitialized && event.getRepeatCount() == 0) {
                    onShutterButtonClick();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                // If we get a dpad center event without any focused view, move
                // the focus to the shutter button and press it.
                if (mFirstTimeInitialized && event.getRepeatCount() == 0) {
                    // Start auto-focus immediately to reduce shutter lag. After
                    // the shutter button gets the focus, onShutterButtonFocus()
                    // will be called again but it is fine.
                    onShutterButtonFocus(true);
                }
                return true;
        }
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (/* mActivity.isInCameraApp() && */mFirstTimeInitialized &&
                    !mActivity.getCameraAppUI().isInIntentReview()) {
                    if (mUI.isCountingDown()) {
                        cancelCountDown();
                    } else {
                        mVolumeButtonClickedFlag = true;
                        onShutterButtonClick();
                    }
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_FOCUS:
                if (mFirstTimeInitialized) {
                    onShutterButtonFocus(false);
                }
                return true;
        }
        return false;
    }

    private void closeCamera() {
        if (mCameraDevice != null) {
            stopFaceDetection();
            mCameraDevice.setZoomChangeListener(null);
            mCameraDevice.setFaceDetectionCallback(null, null);
            mCameraDevice.setErrorCallback(null, null);

            mFaceDetectionStarted = false;
            mActivity.getCameraProvider().releaseCamera(mCameraDevice.getCameraId());
            mCameraDevice = null;
            setCameraState(PREVIEW_STOPPED);
            mFocusManager.onCameraReleased();
        }
    }

    private void setDisplayOrientation() {
        mDisplayRotation = CameraUtil.getDisplayRotation(mActivity);
        Characteristics info =
                mActivity.getCameraProvider().getCharacteristics(mCameraId);
        mDisplayOrientation = info.getPreviewOrientation(mDisplayRotation);
        mCameraDisplayOrientation = mDisplayOrientation;
        mUI.setDisplayOrientation(mDisplayOrientation);
        if (mFocusManager != null) {
            mFocusManager.setDisplayOrientation(mDisplayOrientation);
        }
        // Change the camera display orientation
        if (mCameraDevice != null) {
            mCameraDevice.setDisplayOrientation(mDisplayRotation);
        }
    }

    /** Only called by UI thread. */
    private void setupPreview() {
        Log.i(TAG, "setupPreview");
        mFocusManager.resetTouchFocus();
        startPreview();
    }

    /**
     * Returns whether we can/should start the preview or not.
     */
    private boolean checkPreviewPreconditions() {
        if (mPaused) {
            return false;
        }

        if (mCameraDevice == null) {
            Log.w(TAG, "startPreview: camera device not ready yet.");
            return false;
        }

        SurfaceTexture st = mActivity.getCameraAppUI().getSurfaceTexture();
        if (st == null) {
            Log.w(TAG, "startPreview: surfaceTexture is not ready.");
            return false;
        }

        if (!mCameraPreviewParamsReady) {
            Log.w(TAG, "startPreview: parameters for preview is not ready.");
            return false;
        }
        return true;
    }

    /**
     * The start/stop preview should only run on the UI thread.
     */
    private void startPreview() {
        if (mCameraDevice == null) {
            Log.i(TAG, "attempted to start preview before camera device");
            // do nothing
            return;
        }

        if (!checkPreviewPreconditions()) {
            return;
        }

        mCameraDevice.setErrorCallback(mHandler, mErrorCallback);
        setDisplayOrientation();

        if (!mSnapshotOnIdle) {
            // If the focus mode is continuous autofocus, call cancelAutoFocus
            // to resume it because it may have been paused by autoFocus call.
            if (mFocusManager.getFocusMode(mCameraSettings.getCurrentFocusMode()) ==
                    CameraCapabilities.FocusMode.CONTINUOUS_PICTURE) {
                mCameraDevice.cancelAutoFocus();
            }
            mFocusManager.setAeAwbLock(false); // Unlock AE and AWB.
        }
        setCameraParameters(UPDATE_PARAM_ALL);

        updateParametersPictureSize();

        mCameraDevice.setPreviewTexture(mActivity.getCameraAppUI().getSurfaceTexture());

        Log.i(TAG, "startPreview");
        // If we're using API2 in portability layers, don't use startPreviewWithCallback()
        // b/17576554
        CameraAgent.CameraStartPreviewCallback startPreviewCallback =
            new CameraAgent.CameraStartPreviewCallback() {
                @Override
                public void onPreviewStarted() {
                    mFocusManager.onPreviewStarted();
                    PhotoModule.this.onPreviewStarted();
                    SessionStatsCollector.instance().previewActive(true);
                    if (mSnapshotOnIdle) {
                        mHandler.post(mDoSnapRunnable);
                    }
                }
            };
        if (GservicesHelper.useCamera2ApiThroughPortabilityLayer(mActivity)) {
            mCameraDevice.startPreview();
            startPreviewCallback.onPreviewStarted();
        } else {
            mCameraDevice.startPreviewWithCallback(new Handler(Looper.getMainLooper()),
                    startPreviewCallback);
        }
    }

    @Override
    public void stopPreview() {
        if (mCameraDevice != null && mCameraState != PREVIEW_STOPPED) {
            Log.i(TAG, "stopPreview");
            mCameraDevice.stopPreview();
            mFaceDetectionStarted = false;
        }
        setCameraState(PREVIEW_STOPPED);
        if (mFocusManager != null) {
            mFocusManager.onPreviewStopped();
        }
        SessionStatsCollector.instance().previewActive(false);
    }

    @Override
    public void onSettingChanged(SettingsManager settingsManager, String key) {
        if (key.equals(Keys.KEY_FLASH_MODE)) {
            updateParametersFlashMode();
        }
        if (key.equals(Keys.KEY_CAMERA_HDR)) {
            if (settingsManager.getBoolean(SettingsManager.SCOPE_GLOBAL,
                                           Keys.KEY_CAMERA_HDR)) {
                // HDR is on.
                mAppController.getButtonManager().disableButton(ButtonManager.BUTTON_FLASH);
                mFlashModeBeforeSceneMode = settingsManager.getString(
                        mAppController.getCameraScope(), Keys.KEY_FLASH_MODE);
            } else {
                if (mFlashModeBeforeSceneMode != null) {
                    settingsManager.set(mAppController.getCameraScope(),
                                        Keys.KEY_FLASH_MODE,
                                        mFlashModeBeforeSceneMode);
                    updateParametersFlashMode();
                    mFlashModeBeforeSceneMode = null;
                }
                mAppController.getButtonManager().enableButton(ButtonManager.BUTTON_FLASH);
            }
        }

        if (mCameraDevice != null) {
            mCameraDevice.applySettings(mCameraSettings);
        }
    }

    private void updateCameraParametersInitialize() {
        // Reset preview frame rate to the maximum because it may be lowered by
        // video camera application.
        int[] fpsRange = CameraUtil.getPhotoPreviewFpsRange(mCameraCapabilities);
        if (fpsRange != null && fpsRange.length > 0) {
            mCameraSettings.setPreviewFpsRange(fpsRange[0], fpsRange[1]);
        }

        mCameraSettings.setRecordingHintEnabled(false);

        if (mCameraCapabilities.supports(CameraCapabilities.Feature.VIDEO_STABILIZATION)) {
            mCameraSettings.setVideoStabilization(false);
        }
    }

    private void updateCameraParametersZoom() {
        // Set zoom.
        if (mCameraCapabilities.supports(CameraCapabilities.Feature.ZOOM)) {
            mCameraSettings.setZoomRatio(mZoomValue);
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void setAutoExposureLockIfSupported() {
        if (mAeLockSupported) {
            mCameraSettings.setAutoExposureLock(mFocusManager.getAeAwbLock());
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void setAutoWhiteBalanceLockIfSupported() {
        if (mAwbLockSupported) {
            mCameraSettings.setAutoWhiteBalanceLock(mFocusManager.getAeAwbLock());
        }
    }

    private void setFocusAreasIfSupported() {
        if (mFocusAreaSupported) {
            mCameraSettings.setFocusAreas(mFocusManager.getFocusAreas());
        }
    }

    private void setMeteringAreasIfSupported() {
        if (mMeteringAreaSupported) {
            mCameraSettings.setMeteringAreas(mFocusManager.getMeteringAreas());
        }
    }

    private void updateCameraParametersPreference() {
        // some monkey tests can get here when shutting the app down
        // make sure mCameraDevice is still valid, b/17580046
        if (mCameraDevice == null) {
            return;
        }

        setAutoExposureLockIfSupported();
        setAutoWhiteBalanceLockIfSupported();
        setFocusAreasIfSupported();
        setMeteringAreasIfSupported();

        // Initialize focus mode.
        mFocusManager.overrideFocusMode(null);
        mCameraSettings
                .setFocusMode(mFocusManager.getFocusMode(mCameraSettings.getCurrentFocusMode()));
        SessionStatsCollector.instance().autofocusActive(
                mFocusManager.getFocusMode(mCameraSettings.getCurrentFocusMode()) ==
                        CameraCapabilities.FocusMode.CONTINUOUS_PICTURE
        );

        // Set JPEG quality.
        updateParametersPictureQuality();

        // For the following settings, we need to check if the settings are
        // still supported by latest driver, if not, ignore the settings.

        // Set exposure compensation
        updateParametersExposureCompensation();

        // Set the scene mode: also sets flash and white balance.
        updateParametersSceneMode();

        if (mContinuousFocusSupported && ApiHelper.HAS_AUTO_FOCUS_MOVE_CALLBACK) {
            updateAutoFocusMoveCallback();
        }
    }

    /**
     * This method sets picture size parameters. Size parameters should only be
     * set when the preview is stopped, and so this method is only invoked in
     * {@link #startPreview()} just before starting the preview.
     */
    private void updateParametersPictureSize() {
        if (mCameraDevice == null) {
            Log.w(TAG, "attempting to set picture size without caemra device");
            return;
        }

        SettingsManager settingsManager = mActivity.getSettingsManager();
        String pictureSizeKey = isCameraFrontFacing() ? Keys.KEY_PICTURE_SIZE_FRONT
            : Keys.KEY_PICTURE_SIZE_BACK;
        String pictureSize = settingsManager.getString(SettingsManager.SCOPE_GLOBAL,
                                                       pictureSizeKey);

        List<Size> supported = mCameraCapabilities.getSupportedPhotoSizes();
        CameraPictureSizesCacher.updateSizesForCamera(mAppController.getAndroidContext(),
                mCameraDevice.getCameraId(), supported);
        SettingsUtil.setCameraPictureSize(pictureSize, supported, mCameraSettings,
                mCameraDevice.getCameraId());

        Size size = SettingsUtil.getPhotoSize(pictureSize, supported,
                mCameraDevice.getCameraId());
        if (ApiHelper.IS_NEXUS_5) {
            if (ResolutionUtil.NEXUS_5_LARGE_16_BY_9.equals(pictureSize)) {
                mShouldResizeTo16x9 = true;
            } else {
                mShouldResizeTo16x9 = false;
            }
        }

        // Set a preview size that is closest to the viewfinder height and has
        // the right aspect ratio.
        List<Size> sizes = mCameraCapabilities.getSupportedPreviewSizes();
        Size optimalSize = CameraUtil.getOptimalPreviewSize(mActivity, sizes,
                (double) size.width() / size.height());
        Size original = mCameraSettings.getCurrentPreviewSize();
        if (!optimalSize.equals(original)) {
            Log.v(TAG, "setting preview size. optimal: " + optimalSize + "original: " + original);
            mCameraSettings.setPreviewSize(optimalSize);

            mCameraDevice.applySettings(mCameraSettings);
            mCameraSettings = mCameraDevice.getSettings();
        }

        if (optimalSize.width() != 0 && optimalSize.height() != 0) {
            Log.v(TAG, "updating aspect ratio");
            mUI.updatePreviewAspectRatio((float) optimalSize.width()
                    / (float) optimalSize.height());
        }
        Log.d(TAG, "Preview size is " + optimalSize);
    }

    private void updateParametersPictureQuality() {
        int jpegQuality = CameraProfile.getJpegEncodingQualityParameter(mCameraId,
                CameraProfile.QUALITY_HIGH);
        mCameraSettings.setPhotoJpegCompressionQuality(jpegQuality);
    }

    private void updateParametersExposureCompensation() {
        SettingsManager settingsManager = mActivity.getSettingsManager();
        if (settingsManager.getBoolean(SettingsManager.SCOPE_GLOBAL,
                                       Keys.KEY_EXPOSURE_COMPENSATION_ENABLED)) {
            int value = settingsManager.getInteger(mAppController.getCameraScope(),
                                                   Keys.KEY_EXPOSURE);
            int max = mCameraCapabilities.getMaxExposureCompensation();
            int min = mCameraCapabilities.getMinExposureCompensation();
            if (value >= min && value <= max) {
                mCameraSettings.setExposureCompensationIndex(value);
            } else {
                Log.w(TAG, "invalid exposure range: " + value);
            }
        } else {
            // If exposure compensation is not enabled, reset the exposure compensation value.
            setExposureCompensation(0);
        }

    }

    private void updateParametersSceneMode() {
        CameraCapabilities.Stringifier stringifier = mCameraCapabilities.getStringifier();
        SettingsManager settingsManager = mActivity.getSettingsManager();

        mSceneMode = stringifier.
            sceneModeFromString(settingsManager.getString(mAppController.getCameraScope(),
                                                          Keys.KEY_SCENE_MODE));
        if (mCameraCapabilities.supports(mSceneMode)) {
            if (mCameraSettings.getCurrentSceneMode() != mSceneMode) {
                mCameraSettings.setSceneMode(mSceneMode);

                // Setting scene mode will change the settings of flash mode,
                // white balance, and focus mode. Here we read back the
                // parameters, so we can know those settings.
                mCameraDevice.applySettings(mCameraSettings);
                mCameraSettings = mCameraDevice.getSettings();
            }
        } else {
            mSceneMode = mCameraSettings.getCurrentSceneMode();
            if (mSceneMode == null) {
                mSceneMode = CameraCapabilities.SceneMode.AUTO;
            }
        }

        if (CameraCapabilities.SceneMode.AUTO == mSceneMode) {
            // Set flash mode.
            updateParametersFlashMode();

            // Set focus mode.
            mFocusManager.overrideFocusMode(null);
            mCameraSettings.setFocusMode(
                    mFocusManager.getFocusMode(mCameraSettings.getCurrentFocusMode()));
        } else {
            mFocusManager.overrideFocusMode(mCameraSettings.getCurrentFocusMode());
        }
    }

    private void updateParametersFlashMode() {
        SettingsManager settingsManager = mActivity.getSettingsManager();

        CameraCapabilities.FlashMode flashMode = mCameraCapabilities.getStringifier()
            .flashModeFromString(settingsManager.getString(mAppController.getCameraScope(),
                                                           Keys.KEY_FLASH_MODE));
        if (mCameraCapabilities.supports(flashMode)) {
            mCameraSettings.setFlashMode(flashMode);
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void updateAutoFocusMoveCallback() {
        if (mCameraDevice == null) {
            return;
        }
        if (mCameraSettings.getCurrentFocusMode() ==
                CameraCapabilities.FocusMode.CONTINUOUS_PICTURE) {
            mCameraDevice.setAutoFocusMoveCallback(mHandler,
                    (CameraAFMoveCallback) mAutoFocusMoveCallback);
        } else {
            mCameraDevice.setAutoFocusMoveCallback(null, null);
        }
    }

    /**
     * Sets the exposure compensation to the given value and also updates settings.
     *
     * @param value exposure compensation value to be set
     */
    public void setExposureCompensation(int value) {
        int max = mCameraCapabilities.getMaxExposureCompensation();
        int min = mCameraCapabilities.getMinExposureCompensation();
        if (value >= min && value <= max) {
            mCameraSettings.setExposureCompensationIndex(value);
            SettingsManager settingsManager = mActivity.getSettingsManager();
            settingsManager.set(mAppController.getCameraScope(),
                                Keys.KEY_EXPOSURE, value);
        } else {
            Log.w(TAG, "invalid exposure range: " + value);
        }
    }

    // We separate the parameters into several subsets, so we can update only
    // the subsets actually need updating. The PREFERENCE set needs extra
    // locking because the preference can be changed from GLThread as well.
    private void setCameraParameters(int updateSet) {
        if ((updateSet & UPDATE_PARAM_INITIALIZE) != 0) {
            updateCameraParametersInitialize();
        }

        if ((updateSet & UPDATE_PARAM_ZOOM) != 0) {
            updateCameraParametersZoom();
        }

        if ((updateSet & UPDATE_PARAM_PREFERENCE) != 0) {
            updateCameraParametersPreference();
        }

        if (mCameraDevice != null) {
            mCameraDevice.applySettings(mCameraSettings);
        }
    }

    // If the Camera is idle, update the parameters immediately, otherwise
    // accumulate them in mUpdateSet and update later.
    private void setCameraParametersWhenIdle(int additionalUpdateSet) {
        mUpdateSet |= additionalUpdateSet;
        if (mCameraDevice == null) {
            // We will update all the parameters when we open the device, so
            // we don't need to do anything now.
            mUpdateSet = 0;
            return;
        } else if (isCameraIdle()) {
            setCameraParameters(mUpdateSet);
            updateSceneMode();
            mUpdateSet = 0;
        } else {
            if (!mHandler.hasMessages(MSG_SET_CAMERA_PARAMETERS_WHEN_IDLE)) {
                mHandler.sendEmptyMessageDelayed(MSG_SET_CAMERA_PARAMETERS_WHEN_IDLE, 1000);
            }
        }
    }

    @Override
    public boolean isCameraIdle() {
        return (mCameraState == IDLE) ||
                (mCameraState == PREVIEW_STOPPED) ||
                ((mFocusManager != null) && mFocusManager.isFocusCompleted()
                && (mCameraState != SWITCHING_CAMERA));
    }

    @Override
    public boolean isImageCaptureIntent() {
        String action = mActivity.getIntent().getAction();
        return (MediaStore.ACTION_IMAGE_CAPTURE.equals(action)
        || CameraActivity.ACTION_IMAGE_CAPTURE_SECURE.equals(action));
    }

    private void setupCaptureParams() {
        Bundle myExtras = mActivity.getIntent().getExtras();
        if (myExtras != null) {
            mSaveUri = (Uri) myExtras.getParcelable(MediaStore.EXTRA_OUTPUT);
            mCropValue = myExtras.getString("crop");
        }
    }

    private void initializeCapabilities() {
        mCameraCapabilities = mCameraDevice.getCapabilities();
        mFocusAreaSupported = mCameraCapabilities.supports(CameraCapabilities.Feature.FOCUS_AREA);
        mMeteringAreaSupported = mCameraCapabilities.supports(CameraCapabilities.Feature.METERING_AREA);
        mAeLockSupported = mCameraCapabilities.supports(CameraCapabilities.Feature.AUTO_EXPOSURE_LOCK);
        mAwbLockSupported = mCameraCapabilities.supports(CameraCapabilities.Feature.AUTO_WHITE_BALANCE_LOCK);
        mContinuousFocusSupported =
                mCameraCapabilities.supports(CameraCapabilities.FocusMode.CONTINUOUS_PICTURE);
    }

    @Override
    public void onZoomChanged(float ratio) {
        // Not useful to change zoom value when the activity is paused.
        if (mPaused) {
            return;
        }
        mZoomValue = ratio;
        if (mCameraSettings == null || mCameraDevice == null) {
            return;
        }
        // Set zoom parameters asynchronously
        mCameraSettings.setZoomRatio(mZoomValue);
        mCameraDevice.applySettings(mCameraSettings);
    }

    @Override
    public int getCameraState() {
        return mCameraState;
    }

    @Override
    public void onMemoryStateChanged(int state) {
        mAppController.setShutterEnabled(state == MemoryManager.STATE_OK);
    }

    @Override
    public void onLowMemory() {
        // Not much we can do in the photo module.
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        float[] data;
        if (type == Sensor.TYPE_ACCELEROMETER) {
            data = mGData;
        } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
            data = mMData;
        } else {
            // we should not be here.
            return;
        }
        for (int i = 0; i < 3; i++) {
            data[i] = event.values[i];
        }
        float[] orientation = new float[3];
        SensorManager.getRotationMatrix(mR, null, mGData, mMData);
        SensorManager.getOrientation(mR, orientation);
        mHeading = (int) (orientation[0] * 180f / Math.PI) % 360;
        if (mHeading < 0) {
            mHeading += 360;
        }
    }

    // For debugging only.
    public void setDebugUri(Uri uri) {
        mDebugUri = uri;
    }

    // For debugging only.
    private void saveToDebugUri(byte[] data) {
        if (mDebugUri != null) {
            OutputStream outputStream = null;
            try {
                outputStream = mContentResolver.openOutputStream(mDebugUri);
                outputStream.write(data);
                outputStream.close();
            } catch (IOException e) {
                Log.e(TAG, "Exception while writing debug jpeg file", e);
            } finally {
                CameraUtil.closeSilently(outputStream);
            }
        }
    }

    @Override
    public void onRemoteShutterPress() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                focusAndCapture();
            }
        });
    }

    /**
     * This class manages the loading/releasing/playing of the sounds needed for
     * countdown timer.
     */
    private class CountdownSoundPlayer {
        private SoundPool mSoundPool;
        private int mTimerIncrement;
        private int mTimerFinalSecond;

        void loadSounds() {
            // Load the sounds.
            if (mSoundPool == null) {
                mSoundPool = new SoundPool(1, AudioManager.STREAM_NOTIFICATION, 0);
                mTimerIncrement = mSoundPool.load(mAppController.getAndroidContext(), R.raw.timer_increment, 1);
                mTimerFinalSecond = mSoundPool.load(mAppController.getAndroidContext(), R.raw.timer_final_second, 1);
            }
        }

        void onRemainingSecondsChanged(int newVal) {
            if (mSoundPool == null) {
                Log.e(TAG, "Cannot play sound - they have not been loaded.");
                return;
            }
            if (newVal == 1) {
                mSoundPool.play(mTimerFinalSecond, 1.0f, 1.0f, 0, 0, 1.0f);
            } else if (newVal == 2 || newVal == 3) {
                mSoundPool.play(mTimerIncrement, 1.0f, 1.0f, 0, 0, 1.0f);
            }
        }

        void release() {
            if (mSoundPool != null) {
                mSoundPool.release();
                mSoundPool = null;
            }
        }
    }
}
