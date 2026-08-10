package com.autoconfirm.smsforwarder;

import android.app.Activity;
import android.os.Bundle;

public class FakeSmsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
