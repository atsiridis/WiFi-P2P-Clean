package nl.os3.studlab.kiev.wifi_p2p;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

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

    public void displayPeers(Collection<String> peers) {
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

    public void displaySID(String sid) {
        TextView view = (TextView) findViewById(R.id.tv_sid);

        if (view != null) {
            view.setText(sid);
        }
    }

    public void sendBroadcast(View v) {
        //app.sendBroadcast();
        app.setTimer();
    }

    public void exit(View view)
    {
        app.exit(0);
    }
}
