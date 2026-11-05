package com.qchm.xyz.profile;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.qchm.xyz.R;


public class CheckInDialog extends Dialog {
    String day ;

    public CheckInDialog(@NonNull Context context, String day ) {
        super(context, R.style.myChooseDialog);
        this.day = day;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_check_in2);
        setWidows();
        TextView tvDay = findViewById(R.id.tvDay);
        tvDay.setText(getDayDescription());
        findViewById(R.id.btn_submit).setOnClickListener(v -> dismiss());
    }

    public String getDayDescription() {
        switch (day) {
            case "1":
                return "第一天";
            case "2":
                return "第二天";
            case "3":
                return "第三天";
            case "4":
                return "第四天";
            case "5":
                return "第五天";
            case "6":
                return "第六天";
            case "7":
                return "第七天";
            default:
                return "未知天数";
        }
    }

    private void setWidows() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.alpha = 1.0f;
        lp.gravity = Gravity.CENTER;
        getWindow().setAttributes(lp);
        this.setCanceledOnTouchOutside(true);
    }
}
