package com.somitsolutions.android.spectrumanalyzer;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Timer;
import java.util.TimerTask;

import ca.uol.aig.fftpack.RealDoubleFFT;

public class SoundRecordAndAnalysisActivity extends Activity {
    int frequency = 8000;
    int channelConfiguration = AudioFormat.CHANNEL_CONFIGURATION_MONO;
    int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;

    AudioRecord audioRecord;
    private RealDoubleFFT transformer;
    int blockSize = 256;
    boolean started = false;

    RecordAudio recordTask;

    static SoundRecordAndAnalysisActivity mainActivity;

    private ObservableWebView mWebView;
    private EditText urlField;
    private final String initWebPage = "http://www.naver.com";
    private final int THRESHOLD_OF_ABOVE = 1500;
    private final int THRESHOLD_OF_BELOW = 110;
    private final int THRESHOLD_OF_COUNT = 50;
    private int failCnt  = 0;
    private long startTime = 0;
    private final int INTERVAL = 3000;

    TextView debugLabel, debugRedLabel;

    private TimerTask mTask = null;
    private Timer mTimer = null;
    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mWebView = (ObservableWebView) findViewById(R.id.webView);
        WebSettings webSettings = mWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        mWebView.setWebViewClient(new MyWebViewClient());
        mWebView.loadUrl(initWebPage);
        mWebView.requestFocus();
        mWebView.setOnScrollChangedCallback(new ObservableWebView.OnScrollChangedCallback() {
            public void onScroll(int l, int t, int oldl, int oldt) {
                int height = (int) Math.floor(mWebView.getContentHeight() * mWebView.getScale());
                int webViewHeight = mWebView.getMeasuredHeight();
                if(mWebView.getScrollY() + webViewHeight >= height){
                    mWebView.post(new Runnable() {
                        public void run() {
                            if (mTimer == null) {
                                Toast toast = Toast.makeText(getApplicationContext(), getApplicationContext().getString(R.string.go_to_top),  Toast.LENGTH_SHORT);
                                toast.setGravity(Gravity.CENTER, 0, 0);
                                toast.show();
                                mTask = new TimerTask() {
                                    @Override
                                    public void run() {
                                        mWebView.post(new Runnable() {
                                            public void run() {
                                                Toast toast = Toast.makeText(getApplicationContext(), getApplicationContext().getString(R.string.top_page),  Toast.LENGTH_SHORT);
                                                toast.setGravity(Gravity.TOP, 0, 0);
                                                toast.show();
                                                mWebView.scrollTo(0, 0);
                                                mTimer.cancel();
                                                mTimer = null;
                                            }
                                        });
                                    }
                                };
                                mTimer = new Timer();
                                mTimer.schedule(mTask, 2000);
                            }
                        }
                    });
                }
            }
        });

        debugLabel = (TextView) findViewById(R.id.debugLabel);
        debugRedLabel = (TextView) findViewById(R.id.debugRedLabel);

        urlField = (EditText) findViewById(R.id.editText);
        urlField.setText(initWebPage);
        urlField.setOnKeyListener(new TextView.OnKeyListener() {
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                // If the event is a key-down event on the "enter" button
                if ((event.getAction() == KeyEvent.ACTION_DOWN) &&
                        (keyCode == KeyEvent.KEYCODE_ENTER)) {
                    mWebView.requestFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Activity.INPUT_METHOD_SERVICE);
                    imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);

                    onClickGoBtn(null);
                    return true;
                }
                return false;
            }
        });
//        urlField.setOnFocusChangeListener(new View.OnFocusChangeListener() {
//            public void onFocusChange(View v, boolean hasFocus)  {
//                Log.d("sung", "hasFocus: " + hasFocus);
//                Toast.makeText(getApplicationContext(), "Focus: " + hasFocus, Toast.LENGTH_SHORT).show();
//                if (hasFocus) {
//                    urlField.selectAll();
//                }
//            }
//        });
    }

    private class MyWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;
        }

        public void onPageFinished(WebView view, String url) {
            if (started == false) {
                started = true;
                recordTask = new RecordAudio();
                recordTask.execute();
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Check if the key event was the Back button and if there's history
        if ((keyCode == KeyEvent.KEYCODE_BACK) && mWebView.canGoBack()) {
            mWebView.goBack();
            return true;
        }
        // If it wasn't the Back key or there's no web page history, bubble up to the default
        // system behavior (probably exit the activity)
        return super.onKeyDown(keyCode, event);
    }

    public void onClickGoBtn(View v) {
        final String prefix = "http://";
        String url = urlField.getText().toString();

        if (!url.equals("")) {
            Log.d("park", "onClickGoBtn: " + url);
            StringBuffer urlAddr = new StringBuffer();
            if (!url.toLowerCase().contains(prefix)) {
                urlAddr.append(prefix).append(url);
            } else {
                urlAddr.append(url);
            }
            url = urlAddr.toString();

            if (!URLUtil.isValidUrl(url)) {
                Toast.makeText(getApplicationContext(), getApplicationContext().getString(R.string.invalid_url), Toast.LENGTH_SHORT).show();
                urlField.setText(prefix);
                return;
            }
            mWebView.loadUrl(url);
            urlField.setText(url);
        } else {
            Toast.makeText(getApplicationContext(), getApplicationContext().getString(R.string.empty_url), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        //mWebView.setFocusable(true);
    }

    private class RecordAudio extends AsyncTask<Void, double[], Void> {
        @Override
        protected Void doInBackground(Void... params) {

            if (isCancelled()) {
                return null;
            }

            int bufferSize = AudioRecord.getMinBufferSize(frequency,
                    channelConfiguration, audioEncoding);
                    /*AudioRecord */
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT, frequency,
                    channelConfiguration, audioEncoding, bufferSize);
            int bufferReadResult;
            short[] buffer = new short[blockSize];
            double[] toTransform = new double[blockSize];
            try {
                audioRecord.startRecording();
            } catch (IllegalStateException e) {
                Log.e("Recording failed", e.toString());

            }
            while (started) {
                bufferReadResult = audioRecord.read(buffer, 0, blockSize);

                if (isCancelled())
                    break;

                for (int i = 0; i < blockSize && i < bufferReadResult; i++) {
                    toTransform[i] = (double) buffer[i] / 32768.0; // signed 16 bit
                }

                transformer.ft(toTransform);

                publishProgress(toTransform);
                if (isCancelled())
                    break;
            }

            try {
                audioRecord.stop();
            } catch (IllegalStateException e) {
                Log.e("Stop failed", e.toString());
            }

            return null;
        }

        protected void onProgressUpdate(double[]... toTransform) {
            Log.e("RecordingProgress", "Displaying in progress");
            double lowPart = 0.0;
            double highPart = 0.0;

            for (int i = 0; i < toTransform[0].length; i++) {
                double downy = toTransform[0][i] * 10;
                Log.d("sung", "downy:  " + downy);

                if (i < (toTransform[0].length / 12 * 5)) {
                    lowPart += Math.abs(downy);
                } else {
                    highPart += Math.abs(downy);
                }
            }

            Double ratio = highPart > 0 ? lowPart / highPart : 0.0;

            String logOut = String.format("LowPart: %07.1f HighPart: %07.1f Ratio: %05.1f", lowPart, highPart, ratio);
            Log.d("park", logOut);

            if (lowPart > THRESHOLD_OF_ABOVE && highPart < THRESHOLD_OF_BELOW) {
                debugLabel.setText(logOut);
                if (ratio > 20) {
                    blowScroll(120);
                } else if (ratio > 17) {
                    blowScroll(100);
                } else if (ratio > 14) {
                    blowScroll(50);
                } else if (ratio > 10) {
                    blowScroll(25);
                }
                failCnt = 0;
            }  else if (lowPart > THRESHOLD_OF_ABOVE  && highPart > THRESHOLD_OF_BELOW && failCnt++ > THRESHOLD_OF_COUNT) {
                long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime  > INTERVAL) {
                    Toast.makeText(getApplicationContext(), getApplicationContext().getString(R.string.blow_too_strong), Toast.LENGTH_SHORT).show();
                    startTime = System.currentTimeMillis();
                }
                failCnt = 0;
            } else {
                debugRedLabel.setText(logOut);
            }
        }

        protected void blowScroll(int velocity) {
//            int x = mWebView.getScrollX();
//            int y = mWebView.getScrollY();

            mWebView.scrollBy(0, velocity);
        }

        protected void onPostExecute(Void result) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException e) {
                Log.e("Stop failed", e.toString());
            }

            if (recordTask != null) {
                recordTask.cancel(true);
            }
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
    }

    static SoundRecordAndAnalysisActivity getMainActivity() {
        return mainActivity;
    }

    public void onStop() {
        super.onStop();

        if (recordTask != null) {
            recordTask.cancel(true);
        }

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    public void onStart() {
        super.onStart();

        transformer = new RealDoubleFFT(blockSize);

        mainActivity = this;
    }

    @Override
    public void onBackPressed() {
        if (recordTask != null) {
            recordTask.cancel(true);
            recordTask = null;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        Log.d("park", "onPause");
        started = false;
        if (recordTask != null) {
            recordTask.cancel(true);
        }
        recordTask.cancel(true);
        recordTask = null;

        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();

        started = true;
        recordTask = new RecordAudio();
        recordTask.execute();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recordTask != null) {
            recordTask.cancel(true);
        }

        if (mTimer != null) {
            mTimer.cancel();
            mTimer = null;
        }
    }
}
    
