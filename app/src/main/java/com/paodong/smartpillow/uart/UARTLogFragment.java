package com.paodong.smartpillow.uart;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.ListFragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.widget.CursorAdapter;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.paodong.smartpillow.R;
import com.paodong.smartpillow.profile.BleProfileService;
import com.paodong.smartpillow.utility.DebugLogger;

import no.nordicsemi.android.log.ILogSession;
import no.nordicsemi.android.log.LogContract;

/**
 * Created by hengfeng on 2017/8/6.
 */

public class UARTLogFragment extends ListFragment implements LoaderManager.LoaderCallbacks<Cursor> {
    private static final String SIS_LOG_SCROLL_POSITION = "sis_scroll_position";
    private static final int LOG_SCROLL_NULL = -1;
    private static final int LOG_SCROLLED_TO_BOTTOM = -2;

    private static final int LOG_REQUEST_ID = 1;
    private static final String[] LOG_PROJECTION = { LogContract.Log._ID, LogContract.Log.TIME, LogContract.Log.LEVEL, LogContract.Log.DATA };

    /** The service UART interface that may be used to send data to the target. */
    private UARTInterface mUARTInterface;
    /** The adapter used to populate the list with log entries. */
    private CursorAdapter mLogAdapter;
    /** The log session created to log events related with the target device. */
    private ILogSession mLogSession;

    private EditText mField;
    private Button mSendButton;

    /** The last list view position. */
    private int mLogScrollPosition;

    /**
     * The receiver that listens for {@link BleProfileService#BROADCAST_CONNECTION_STATE} action.
     */
    // 通过广播来接收ble设备状态的变化
    private final BroadcastReceiver mCommonBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            // This receiver listens only for the BleProfileService.BROADCAST_CONNECTION_STATE action, no need to check it.
            final int state = intent.getIntExtra(BleProfileService.EXTRA_CONNECTION_STATE, BleProfileService.STATE_DISCONNECTED);

            switch (state) {
                case BleProfileService.STATE_CONNECTED: {
                    onDeviceConnected(); // 蓝牙连接上了
                    break;
                }
                case BleProfileService.STATE_DISCONNECTED: {
                    onDeviceDisconnected(); // 蓝牙断开了
                    break;
                }
                case BleProfileService.STATE_CONNECTING:
                case BleProfileService.STATE_DISCONNECTING:
                    // current implementation does nothing in this states
                default:
                    // there should be no other actions
                    break;
            }
        }
    };

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(final ComponentName name, final IBinder service) {
            final UARTService.UARTBinder bleService = (UARTService.UARTBinder) service;
            // 通过绑定服务来获取实际的接口
            mUARTInterface = bleService;
            mLogSession = bleService.getLogSession();

            DebugLogger.d("zhfzhf", "UARTLogFragment: onServiceConnected mUARTInterface="+mUARTInterface+", mLogSession="+mLogSession);

            // Start the loader
            if (mLogSession != null) { // 这里传入callback
                getLoaderManager().restartLoader(LOG_REQUEST_ID, null, UARTLogFragment.this);
            }

            // and notify user if device is connected
            if (bleService.isConnected())
                onDeviceConnected();
        }

        @Override
        public void onServiceDisconnected(final ComponentName name) {
            onDeviceDisconnected();
            mUARTInterface = null;
        }
    };

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 接收广播
        LocalBroadcastManager.getInstance(getActivity()).registerReceiver(mCommonBroadcastReceiver, makeIntentFilter());

        // Load the last log list view scroll position
        if (savedInstanceState != null) { // 记录log 滚动的位置
            mLogScrollPosition = savedInstanceState.getInt(SIS_LOG_SCROLL_POSITION);
        }
    }

    @Override
    public void onStart() {
        super.onStart();

		/*
		 * If the service has not been started before the following lines will not start it. However, if it's running, the Activity will be binded to it
		 * and notified via mServiceConnection.
		 */
        final Intent service = new Intent(getActivity(), UARTService.class);
        DebugLogger.d("zhfzhf", "UARTLogFragment: onStart -----bindService");

        getActivity().bindService(service, mServiceConnection, 0); // we pass 0 as a flag so the service will not be created if not exists
    }

    @Override
    public void onStop() {
        super.onStop();

        try {
            DebugLogger.d("zhfzhf", "UARTLogFragment: onStart -----unbindService");
            getActivity().unbindService(mServiceConnection);
            mUARTInterface = null;
        } catch (final IllegalArgumentException e) {
            // do nothing, we were not connected to the sensor
        }
    }

    @Override
    public void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);

        // Save the last log list view scroll position
        final ListView list = getListView();
        final boolean scrolledToBottom = list.getCount() > 0 && list.getLastVisiblePosition() == list.getCount() - 1;
        outState.putInt(SIS_LOG_SCROLL_POSITION, scrolledToBottom ? LOG_SCROLLED_TO_BOTTOM : list.getFirstVisiblePosition());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 取消广播
        LocalBroadcastManager.getInstance(getActivity()).unregisterReceiver(mCommonBroadcastReceiver);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_feature_uart_log, container, false);

        final EditText field = mField = (EditText) view.findViewById(R.id.field);
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(final TextView v, final int actionId, final KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    onSendClicked(); // 如果按来回车，也是可以发送
                    return true;
                }
                return false;
            }
        });

        final Button sendButton = mSendButton = (Button) view.findViewById(R.id.action_send);
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                onSendClicked();
            }
        });
        return view;
    }

    @Override
    public void onViewCreated(final View view, final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Create the log adapter, initially with null cursor
        mLogAdapter = new UARTLogAdapter(getActivity());
        setListAdapter(mLogAdapter);
    }

    //====================================================================
    @Override
    public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
        switch (id) {
            case LOG_REQUEST_ID: {
                return new CursorLoader(getActivity(), mLogSession.getSessionEntriesUri(), LOG_PROJECTION, null, null, LogContract.Log.TIME);
            }
        }
        return null;
    }

    @Override
    public void onLoadFinished(final Loader<Cursor> loader, final Cursor data) {
        // Here we have to restore the old saved scroll position, or scroll to the bottom if before adding new events it was scrolled to the bottom.
        final ListView list = getListView();
        final int position = mLogScrollPosition;
        final boolean scrolledToBottom = position == LOG_SCROLLED_TO_BOTTOM || (list.getCount() > 0 && list.getLastVisiblePosition() == list.getCount() - 1);
        DebugLogger.d("zhfzhf", "onLoadFinished=====");
        mLogAdapter.swapCursor(data);

        if (position > LOG_SCROLL_NULL) {
            list.setSelectionFromTop(position, 0);
        } else {
            if (scrolledToBottom)
                list.setSelection(list.getCount() - 1);
        }
        mLogScrollPosition = LOG_SCROLL_NULL;
    }

    @Override
    public void onLoaderReset(final Loader<Cursor> loader) {
        mLogAdapter.swapCursor(null);
    }
    //====================================================================

    private void onSendClicked() {
        final String text = mField.getText().toString();

        mUARTInterface.send(text);

        mField.setText(null);
        mField.requestFocus();
    }


    /**
     * Method called when user selected a device on the scanner dialog after the service has been started.
     * Here we may bind this fragment to it.
     */
    public void onServiceStarted() {
        // The service has been started, bind to it
        final Intent service = new Intent(getActivity(), UARTService.class);
        getActivity().bindService(service, mServiceConnection, 0);
    }

    /**
     * This method is called when user closes the pane in horizontal orientation. The EditText is no longer visible so we need to close the soft keyboard here.
     */
    public void onFragmentHidden() {
        InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(mField.getWindowToken(), 0);
    }

    /**
     * Method called when the target device has connected.
     */
    protected void onDeviceConnected() {
        mField.setEnabled(true);
        mSendButton.setEnabled(true);
    }

    /**
     * Method called when user disconnected from the target UART device or the connection was lost.
     */
    protected void onDeviceDisconnected() {
        mField.setEnabled(false);
        mSendButton.setEnabled(false);
    }

    private static IntentFilter makeIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        // 注册连接状态的广播
        intentFilter.addAction(BleProfileService.BROADCAST_CONNECTION_STATE);
        return intentFilter;
    }
}