package com.samuel.verificador;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class FaceGuideOverlay extends View {

    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public FaceGuideOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);

        setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        scrimPaint.setColor(Color.argb(115, 0, 0, 0));
        scrimPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(Color.parseColor("#22D3EE"));
        borderPaint.setStyle(Paint.Style.STROKE);

        float density = getResources()
                .getDisplayMetrics().density;

        borderPaint.setStrokeWidth(3 * density);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(15 * density);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {

        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();

        if (width <= 0 || height <= 0) {
            return;
        }

        float ovalWidth = width * 0.72f;

        float ovalHeight = Math.min(
                height * 0.78f,
                ovalWidth * 1.25f
        );

        float centerX = width / 2f;
        float centerY = height / 2f;

        RectF oval = new RectF(
                centerX - ovalWidth / 2f,
                centerY - ovalHeight / 2f,
                centerX + ovalWidth / 2f,
                centerY + ovalHeight / 2f
        );

        // Oscurecer el exterior del óvalo.
        Path mask = new Path();

        mask.setFillType(Path.FillType.EVEN_ODD);

        mask.addRect(
                0,
                0,
                width,
                height,
                Path.Direction.CW
        );

        mask.addOval(
                oval,
                Path.Direction.CCW
        );

        canvas.drawPath(mask, scrimPaint);

        // Dibujar el marco facial.
        canvas.drawOval(oval, borderPaint);

        // Instrucción superior.
        float density = getResources()
                .getDisplayMetrics().density;

        canvas.drawText(
                "CENTRA TU ROSTRO",
                centerX,
                oval.top - 15 * density,
                textPaint
        );
    }
}