package nl.os3.studlab.kiev.wifi_p2p;

import android.content.Intent;
import android.net.wifi.p2p.WifiP2pDevice;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.Collection;

public class WiFiActivity extends AppCompatActivity {
    private WiFiApplication app;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi);

        app = (WiFiApplication) getApplication();
        app.setActivity(this);
    }

    public void display_peers(Collection<String> peers) {
        Button button;
        LinearLayout linearLayout = (LinearLayout) findViewById(R.id.ll_peers);

        if (linearLayout != null) {
            linearLayout.removeAllViews();

            for (final String remoteSID : peers) {
                button = new Button(this);

                button.setText(remoteSID);

                button.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        app.sendTest(remoteSID);
                    }
                });
                linearLayout.addView(button);
            }
        }
    }

    public void sendBroadcast(View v) {
        app.sendBroadcast();
    }

    public void exit(View view)
    {
        app.exit(0);
    }
}
