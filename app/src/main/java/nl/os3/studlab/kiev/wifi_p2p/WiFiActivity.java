package nl.os3.studlab.kiev.wifi_p2p;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

public class WiFiActivity extends AppCompatActivity {
    private final String TAG = "OS3";
    private WiFiApplication app;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi);

        app = (WiFiApplication) getApplication();
        Log.d(TAG,"WiFiActivity Created");
    }

    public void exit(View view)
    {
        app.exit(0);
    }
}
