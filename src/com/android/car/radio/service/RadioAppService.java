/**
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.radio.service;

import static com.android.car.radio.util.Remote.tryExec;

import android.content.Intent;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager.ProgramInfo;
import android.hardware.radio.RadioTuner;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.media.MediaBrowserServiceCompat;

import com.android.car.broadcastradio.support.media.BrowseTree;
import com.android.car.broadcastradio.support.platform.ProgramSelectorExt;
import com.android.car.radio.audio.AudioStreamController;
import com.android.car.radio.bands.ProgramType;
import com.android.car.radio.media.TunerSession;
import com.android.car.radio.platform.ImageMemoryCache;
import com.android.car.radio.platform.RadioManagerExt;
import com.android.car.radio.storage.RadioStorage;
import com.android.car.radio.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * A service handling hardware tuner session and audio streaming.
 */
public class RadioAppService extends MediaBrowserServiceCompat implements LifecycleOwner {
    private static final String TAG = "BcRadioApp.service";

    public static String ACTION_APP_SERVICE = "com.android.car.radio.ACTION_APP_SERVICE";
    private static final long PROGRAM_LIST_RATE_LIMITING = 1000;

    private final Object mLock = new Object();
    private final LifecycleRegistry mLifecycleRegistry = new LifecycleRegistry(this);
    private final List<IRadioAppCallback> mRadioAppCallbacks = new ArrayList<>();

    private RadioManagerExt mRadioManager;
    @Nullable private RadioTuner mRadioTuner;
    @Nullable private ProgramList mProgramList;

    private RadioStorage mRadioStorage;
    private ImageMemoryCache mImageCache;
    private AudioStreamController mAudioStreamController;

    private BrowseTree mBrowseTree;
    private TunerSession mMediaSession;

    // current observables state for newly bound IRadioAppCallbacks
    private ProgramInfo mCurrentProgram = null;
    private int mCurrentPlaybackState = PlaybackStateCompat.STATE_NONE;
    private long mLastProgramListPush;

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "Starting RadioAppService...");

        RadioAppServiceWrapper wrapper = new RadioAppServiceWrapper(mBinder);

        mRadioManager = new RadioManagerExt(this);
        mRadioStorage = RadioStorage.getInstance(this);
        mImageCache = new ImageMemoryCache(mRadioManager, 1000);
        mAudioStreamController = new AudioStreamController(this, mRadioManager,
                wrapper.getCurrentProgram(), this::onPlaybackStateChanged);

        mRadioTuner = mRadioManager.openSession(mHardwareCallback, null);
        if (mRadioTuner == null) {
            Log.e(TAG, "Couldn't open tuner session");
            return;
        }

        mBrowseTree = new BrowseTree(this, mImageCache);
        mMediaSession = new TunerSession(this, mBrowseTree, wrapper, mImageCache);
        setSessionToken(mMediaSession.getSessionToken());
        mBrowseTree.setAmFmRegionConfig(mRadioManager.getAmFmRegionConfig());
        mRadioStorage.getFavorites().observe(this,
                favs -> mBrowseTree.setFavorites(new HashSet<>(favs)));

        mProgramList = mRadioTuner.getDynamicProgramList(null);
        if (mProgramList != null) {
            mBrowseTree.setProgramList(mProgramList);
            mProgramList.registerListCallback(new ProgramList.ListCallback() {
                @Override
                public void onItemChanged(@NonNull ProgramSelector.Identifier id) {
                    onProgramListChanged();
                }
            });
            mProgramList.addOnCompleteListener(this::pushProgramListUpdate);
        }

        tuneToDefault(null);
        mAudioStreamController.requestMuted(false);

        mLifecycleRegistry.markState(Lifecycle.State.CREATED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mLifecycleRegistry.markState(Lifecycle.State.STARTED);
        if (BrowseTree.ACTION_PLAY_BROADCASTRADIO.equals(intent.getAction())) {
            Log.i(TAG, "Executing general play radio intent");
            mMediaSession.getController().getTransportControls().playFromMediaId(
                    mBrowseTree.getRoot().getRootId(), null);
            return START_NOT_STICKY;
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        mLifecycleRegistry.markState(Lifecycle.State.STARTED);
        if (mRadioTuner == null) return null;
        if (ACTION_APP_SERVICE.equals(intent.getAction())) {
            return mBinder;
        }
        return super.onBind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mLifecycleRegistry.markState(Lifecycle.State.CREATED);
        return false;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Shutting down RadioAppService...");

        mLifecycleRegistry.markState(Lifecycle.State.DESTROYED);

        if (mMediaSession != null) mMediaSession.release();
        mRadioManager.getRadioTunerExt().close();
        close();

        super.onDestroy();
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycleRegistry;
    }

    private void onPlaybackStateChanged(int newState) {
        synchronized (mLock) {
            mCurrentPlaybackState = newState;
            for (IRadioAppCallback callback : mRadioAppCallbacks) {
                tryExec(() -> callback.onPlaybackStateChanged(newState));
            }
        }
    }

    private void onProgramListChanged() {
        synchronized (mLock) {
            if (SystemClock.elapsedRealtime() - mLastProgramListPush > PROGRAM_LIST_RATE_LIMITING) {
                pushProgramListUpdate();
            }
        }
    }

    private void pushProgramListUpdate() {
        List<ProgramInfo> plist = mProgramList.toList();

        synchronized (mLock) {
            mLastProgramListPush = SystemClock.elapsedRealtime();
            for (IRadioAppCallback callback : mRadioAppCallbacks) {
                tryExec(() -> callback.onProgramListChanged(plist));
            }
        }
    }

    private void tuneToDefault(@Nullable ProgramType pt) {
        synchronized (mLock) {
            if (mRadioTuner == null) throw new IllegalStateException("Tuner session is closed");
            if (!mAudioStreamController.preparePlayback(AudioStreamController.OPERATION_TUNE)) {
                return;
            }

            ProgramSelector sel = mRadioStorage.getRecentlySelected(pt);
            if (sel != null) {
                Log.i(TAG, "Restoring recently selected program: " + sel);
                mRadioTuner.tune(sel);
                return;
            }

            Log.i(TAG, "No recently selected program set, seeking forward to not play static");

            // TODO(b/73950974): don't hardcode, pull from tuner config
            long lastChannel;
            if (pt == ProgramType.AM) lastChannel = 1620;
            else lastChannel = 108000;
            mRadioTuner.tune(ProgramSelectorExt.createAmFmSelector(lastChannel));

            mRadioTuner.scan(RadioTuner.DIRECTION_UP, true);
        }
    }

    private void close() {
        synchronized (mLock) {
            mAudioStreamController.requestMuted(true);

            if (mProgramList != null) {
                mProgramList.close();
                mProgramList = null;
            }
            if (mRadioTuner != null) {
                mRadioTuner.close();
                mRadioTuner = null;
            }
        }
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        /* Radio application may restrict who can read its MediaBrowser tree.
         * Our implementation doesn't.
         */
        return mBrowseTree.getRoot();
    }

    @Override
    public void onLoadChildren(final String parentMediaId, final Result<List<MediaItem>> result) {
        mBrowseTree.loadChildren(parentMediaId, result);
    }

    private void onHardwareError() {
        close();
        stopSelf();
        synchronized (mLock) {
            for (IRadioAppCallback callback : mRadioAppCallbacks) {
                tryExec(() -> callback.onHardwareError());
            }
        }
    }

    private IRadioAppService.Stub mBinder = new IRadioAppService.Stub() {
        @Override
        public void addCallback(IRadioAppCallback callback) throws RemoteException {
            synchronized (mLock) {
                if (mCurrentProgram != null) callback.onCurrentProgramChanged(mCurrentProgram);
                callback.onPlaybackStateChanged(mCurrentPlaybackState);
                if (mProgramList != null) callback.onProgramListChanged(mProgramList.toList());
                mRadioAppCallbacks.add(callback);
            }
        }

        @Override
        public void removeCallback(IRadioAppCallback callback) {
            synchronized (mLock) {
                mRadioAppCallbacks.remove(callback);
            }
        }

        @Override
        public void tune(ProgramSelector sel) {
            synchronized (mLock) {
                if (mRadioTuner == null) throw new IllegalStateException("Tuner session is closed");
                if (!mAudioStreamController.preparePlayback(AudioStreamController.OPERATION_TUNE)) {
                    return;
                }
                mRadioTuner.tune(sel);
            }
        }

        @Override
        public void seekForward() {
            synchronized (mLock) {
                if (mRadioTuner == null) throw new IllegalStateException("Tuner session is closed");
                if (!mAudioStreamController.preparePlayback(
                        AudioStreamController.OPERATION_SEEK_FWD)) {
                    return;
                }
                mRadioTuner.scan(RadioTuner.DIRECTION_UP, true);
            }
        }

        @Override
        public void seekBackward() {
            synchronized (mLock) {
                if (mRadioTuner == null) throw new IllegalStateException("Tuner session is closed");
                if (!mAudioStreamController.preparePlayback(
                        AudioStreamController.OPERATION_SEEK_BKW)) {
                    return;
                }
                mRadioTuner.scan(RadioTuner.DIRECTION_DOWN, true);
            }
        }

        @Override
        public void setMuted(boolean muted) {
            mAudioStreamController.requestMuted(muted);
        }

        @Override
        public void switchBand(ProgramType band) {
            tuneToDefault(band);
        }

        @Override
        public boolean isProgramListSupported() {
            return mProgramList != null;
        }
    };

    private RadioTuner.Callback mHardwareCallback = new RadioTuner.Callback() {
        @Override
        public void onProgramInfoChanged(ProgramInfo info) {
            Objects.requireNonNull(info);

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Program info changed: " + info);
            }

            synchronized (mLock) {
                mCurrentProgram = info;

                /* Storing recently selected program might be limited to explicit tune calls only
                 * (including next/prev seek), but the implementation would be nontrivial with the
                 * current API. For now, let's make it simple and make it react to all program
                 * selector changes. */
                mRadioStorage.setRecentlySelected(info.getSelector());

                for (IRadioAppCallback callback : mRadioAppCallbacks) {
                    tryExec(() -> callback.onCurrentProgramChanged(info));
                }
            }
        }

        @Override
        public void onError(int status) {
            switch (status) {
                case RadioTuner.ERROR_HARDWARE_FAILURE:
                case RadioTuner.ERROR_SERVER_DIED:
                    Log.e(TAG, "Fatal hardware error: " + status);
                    onHardwareError();
                    break;
                default:
                    Log.w(TAG, "Hardware error: " + status);
            }
        }

        @Override
        public void onControlChanged(boolean control) {
            if (!control) onHardwareError();
        }
    };
}
