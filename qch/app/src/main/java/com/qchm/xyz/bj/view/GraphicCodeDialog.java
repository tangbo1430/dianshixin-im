package com.qchm.xyz.bj.view;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;

import com.bumptech.glide.Glide;
import com.qchm.xyz.R;

public class GraphicCodeDialog extends Dialog {
    EditText et_code;
    AppCompatImageView graphic_code;
    Button btn_confirm;
    OnGraphicCodeListener onGraphicCodeListener;

    public GraphicCodeDialog(@NonNull Context context) {
        super(context, R.style.myChooseDialog);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_graphic_code);
        setWidows();
        et_code = findViewById(R.id.et_code);
        graphic_code = findViewById(R.id.graphic_code);
        btn_confirm = findViewById(R.id.btn_confirm);
        btn_confirm.setOnClickListener(v -> {
            if (et_code.getText().toString().isEmpty()) return;
            if (onGraphicCodeListener == null) return;
            onGraphicCodeListener.onGraphicCode(et_code.getText().toString());
        });
    }

    public void setImageClick(View.OnClickListener listener) {
        graphic_code.setOnClickListener(listener);
    }

    public void setImage(String image) {
        Glide.with(getContext()).load(image).into(graphic_code);
    }

    public interface OnGraphicCodeListener {
        void onGraphicCode(String code);
    }

    public void setOnGraphicCodeListener(OnGraphicCodeListener onGraphicCodeListener) {
        this.onGraphicCodeListener = onGraphicCodeListener;
    }

    private void setWidows() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.alpha = 1.0f;
        lp.gravity = Gravity.CENTER;
        getWindow().setAttributes(lp);
        this.setCanceledOnTouchOutside(true);
    }
}
