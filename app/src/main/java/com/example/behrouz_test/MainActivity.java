package com.example.behrouz_test;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HTTP_CALL";

    private EditText inputField;     // ARTNR
    private EditText nameField;
    private EditText meField;
    private EditText bestandField;   // NACH
    private EditText mengeField;

    private Spinner lagerSpinner;    // VON

    private TextView lastTryText;
    private TextView lastTryMark;

    private ImageButton playButton;

    private final List<LagerItem> currentLagerItems = new ArrayList<>();
    private ArrayAdapter<LagerItem> lagerAdapter;

    // Prevent “auto-selected first item” from triggering jump immediately after loading data
    private boolean suppressNextJump = false;

    private final DecimalFormat df = new DecimalFormat("0.##");
    private String lastNachSent = "";
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // -------- Spinner popup control --------
    private volatile boolean spinnerPopupOpen = false;

    // NEW: true after popup opens; consumed on first user selection while open
    private volatile boolean awaitingSpinnerPick = false;

    // Remember NACH focusability while popup is open
    private boolean nachWasFocusable = true;
    private boolean nachWasFocusableInTouch = true;

    // Remember ARTNR focusability while popup is open (prevents focus restoring to ARTNR)
    private boolean artnrWasFocusable = true;
    private boolean artnrWasFocusableInTouch = true;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputField = findViewById(R.id.inputField);
        nameField = findViewById(R.id.nameField);
        meField = findViewById(R.id.meField);
        lagerSpinner = findViewById(R.id.lagerSpinner);
        bestandField = findViewById(R.id.bestandField); // NACH
        mengeField = findViewById(R.id.menge);

        lastTryText = findViewById(R.id.lastTryText);
        lastTryMark = findViewById(R.id.lastTryMark);

        playButton = findViewById(R.id.playButton);

        // Initial hide is fine
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        lagerAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, currentLagerItems);
        lagerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        lagerSpinner.setAdapter(lagerAdapter);

        // Best effort dismiss hook (not relied upon)
        hookSpinnerDismissListener();

        // When spinner opens (user tap) -> mark popup as open + lock focus stealing
        lagerSpinner.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                onSpinnerPopupOpening();
            }
            return false;
        });

        // CRITICAL: jump to NACH after the FIRST selection event while popup is open
        lagerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                if (suppressNextJump) return;

                // Only react while popup is open and we are awaiting a pick
                if (!spinnerPopupOpen) return;
                if (!awaitingSpinnerPick) return;

                // Consume the pick (so it only happens once)
                awaitingSpinnerPick = false;

                // Force focus to NACH after selection
                forceJumpToNachAfterSpinnerSelection();
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        // Ensure tapping fields always shows keyboard
        bestandField.setOnClickListener(v -> showKeyboardHard(bestandField));
        mengeField.setOnClickListener(v -> showKeyboardHard(mengeField));
        inputField.setOnClickListener(v -> showKeyboardHard(inputField));

        // Clear red warning as soon as user starts correcting NACH
        bestandField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { setNachFieldRed(false); }
            @Override public void afterTextChanged(Editable s) { }
        });

        // Clear red warning as soon as user starts correcting MENGE
        mengeField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { setMengeFieldRed(false); }
            @Override public void afterTextChanged(Editable s) { }
        });

        // NACH: when focus leaves -> POST /lagernr ; when focus enters -> show keyboard
        bestandField.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String nachText = bestandField.getText().toString().trim();
                if (nachText.isEmpty()) return;
                if (nachText.equals(lastNachSent)) return;

                lastNachSent = nachText;
                new Thread(() -> postLagernr(nachText)).start();
            } else {
                if (!spinnerPopupOpen) showKeyboardHard(bestandField);
            }
        });

        // Next from NACH -> MENGE
        bestandField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                mengeField.requestFocus();
                showKeyboardHard(mengeField);
                return true;
            }
            return false;
        });

        mengeField.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showKeyboardHard(mengeField);
        });

        // Artnr scan/enter -> GET /artikel
        inputField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                String inputText = inputField.getText().toString().trim();
                if (inputText.isEmpty()) {
                    Toast.makeText(this, "Bitte Artikel-Nr scannen", Toast.LENGTH_SHORT).show();
                    setArtikelFieldRed(true);
                    return true;
                }

                setArtikelFieldRed(false);
                new Thread(() -> makeArtikelRequest(inputText)).start();
                return true;
            }
            return false;
        });

        inputField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { setArtikelFieldRed(false); }
            @Override public void afterTextChanged(Editable s) { }
        });

        ImageButton clearIconButton = findViewById(R.id.clearIconButton);
        clearIconButton.setOnClickListener(v -> clearAllAndFocus());

        playButton.setOnClickListener(v -> {
            forceHideKeyboardOnly();
            new Thread(this::sendUmbuchungPost).start();
        });

        inputField.requestFocus();
    }

    // ===================== Spinner popup open/close handling =====================

    private void onSpinnerPopupOpening() {
        spinnerPopupOpen = true;
        awaitingSpinnerPick = true;

        // Prevent NACH and ARTNR from stealing focus while popup is open
        lockNachFocus(true);
        lockArtNrFocus(true);

        // Hide keyboard and clear focus
        forceHideKeyboardOnly();
        clearEditTextFocus();

        // Put focus onto a non-EditText view so Android doesn't "restore" ARTNR
        android.view.View root = findViewById(android.R.id.content);
        if (root != null) {
            root.setFocusableInTouchMode(true);
            root.requestFocus();
        }
    }

    // Best-effort cleanup. Even if it never fires, the force-jump handles focus.
    private void onSpinnerPopupDismissed() {
        spinnerPopupOpen = false;
        lockNachFocus(false);
        lockArtNrFocus(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
    }

    /**
     * OEM-proof focus jump: assert focus twice, and keep ARTNR locked while animations complete.
     */
    private void forceJumpToNachAfterSpinnerSelection() {
        // Keep ARTNR locked so it cannot steal focus during close animation
        lockArtNrFocus(true);
        lockNachFocus(false);

        // Attempt 1 (soon after selection)
        ui.postDelayed(() -> {
            if (bestandField == null) return;

            bestandField.setFocusable(true);
            bestandField.setFocusableInTouchMode(true);
            try { bestandField.setShowSoftInputOnFocus(true); } catch (Throwable ignored) {}

            bestandField.requestFocus();
            bestandField.requestFocusFromTouch();
            showKeyboardHard(bestandField);
        }, 80);

        // Attempt 2 (after many OEMs restore focus)
        ui.postDelayed(() -> {
            if (bestandField == null) return;

            bestandField.requestFocus();
            bestandField.requestFocusFromTouch();
            showKeyboardHard(bestandField);

            // Now unlock ARTNR again
            lockArtNrFocus(false);

            // End spinner flow even if dismiss hook is unreliable
            spinnerPopupOpen = false;
        }, 550); // adjust 380–650 if needed
    }

    private void hookSpinnerDismissListener() {
        try {
            Field popupField = lagerSpinner.getClass().getDeclaredField("mPopup");
            popupField.setAccessible(true);
            Object popup = popupField.get(lagerSpinner);
            if (popup == null) return;

            try {
                java.lang.reflect.Method setOnDismiss =
                        popup.getClass().getMethod("setOnDismissListener", android.widget.PopupWindow.OnDismissListener.class);

                setOnDismiss.invoke(popup, (android.widget.PopupWindow.OnDismissListener) this::onSpinnerPopupDismissed);
            } catch (NoSuchMethodException ignored) {
                try {
                    java.lang.reflect.Method getPopupWindow = popup.getClass().getMethod("getPopupWindow");
                    Object pw = getPopupWindow.invoke(popup);
                    if (pw instanceof android.widget.PopupWindow) {
                        ((android.widget.PopupWindow) pw).setOnDismissListener(this::onSpinnerPopupDismissed);
                    }
                } catch (Throwable ignored2) { }
            }
        } catch (Throwable ignored) { }
    }

    private void lockNachFocus(boolean lock) {
        if (bestandField == null) return;

        if (lock) {
            nachWasFocusable = bestandField.isFocusable();
            nachWasFocusableInTouch = bestandField.isFocusableInTouchMode();

            bestandField.setFocusable(false);
            bestandField.setFocusableInTouchMode(false);
            bestandField.clearFocus();
        } else {
            bestandField.setFocusable(nachWasFocusable);
            bestandField.setFocusableInTouchMode(nachWasFocusableInTouch);
        }
    }

    private void lockArtNrFocus(boolean lock) {
        if (inputField == null) return;

        if (lock) {
            artnrWasFocusable = inputField.isFocusable();
            artnrWasFocusableInTouch = inputField.isFocusableInTouchMode();

            inputField.setFocusable(false);
            inputField.setFocusableInTouchMode(false);
            inputField.clearFocus();
        } else {
            inputField.setFocusable(artnrWasFocusable);
            inputField.setFocusableInTouchMode(artnrWasFocusableInTouch);
        }
    }

    private void clearEditTextFocus() {
        if (inputField != null) inputField.clearFocus();
        if (bestandField != null) bestandField.clearFocus();
        if (mengeField != null) mengeField.clearFocus();
        if (nameField != null) nameField.clearFocus();
        if (meField != null) meField.clearFocus();
    }

    private void forceHideKeyboardOnly() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;

        android.view.View decor = getWindow().getDecorView();
        imm.hideSoftInputFromWindow(decor.getWindowToken(), 0);
    }

    // IMPORTANT: no toggleSoftInput() here.
    private void showKeyboardHard(EditText et) {
        if (et == null) return;

        et.setFocusable(true);
        et.setFocusableInTouchMode(true);
        try { et.setShowSoftInputOnFocus(true); } catch (Throwable ignored) { }

        et.requestFocus();
        et.requestFocusFromTouch();

        et.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    // ===================== last try UI =====================

    private void setLastTry(boolean success, String artnr, String von, String nach, String menge, String time) {
        final String text = artnr + " - " + von + " - " + nach + " - " + menge + " - " + time;

        runOnUiThread(() -> {
            if (lastTryText != null) lastTryText.setText(text);

            if (lastTryMark != null) {
                if (success) {
                    lastTryMark.setText("✓");
                    lastTryMark.setTextColor(Color.GREEN);
                } else {
                    lastTryMark.setText("✗");
                    lastTryMark.setTextColor(Color.RED);
                }
            }
        });
    }

    private void setPlayEnabled(boolean enabled) {
        runOnUiThread(() -> {
            if (playButton == null) return;
            playButton.setEnabled(enabled);
            playButton.setAlpha(enabled ? 1.0f : 0.35f);
        });
    }

    // ===================== red-field helpers =====================

    private void setArtikelFieldRed(boolean red) {
        if (inputField == null) return;
        Drawable bg = inputField.getBackground();
        if (bg == null) return;

        bg = bg.mutate();
        if (red) bg.setColorFilter(Color.RED, PorterDuff.Mode.SRC_ATOP);
        else bg.clearColorFilter();
        inputField.invalidate();
    }

    private void setNachFieldRed(boolean red) {
        if (bestandField == null) return;
        Drawable bg = bestandField.getBackground();
        if (bg == null) return;

        bg = bg.mutate();
        if (red) bg.setColorFilter(Color.RED, PorterDuff.Mode.SRC_ATOP);
        else bg.clearColorFilter();
        bestandField.invalidate();
    }

    private void setMengeFieldRed(boolean red) {
        if (mengeField == null) return;
        Drawable bg = mengeField.getBackground();
        if (bg == null) return;

        bg = bg.mutate();
        if (red) bg.setColorFilter(Color.RED, PorterDuff.Mode.SRC_ATOP);
        else bg.clearColorFilter();
        mengeField.invalidate();
    }

    private void clearAllAndFocus() {
        inputField.setText("");
        setArtikelFieldRed(false);

        nameField.setText("");
        meField.setText("");

        bestandField.setText("");
        setNachFieldRed(false);

        mengeField.setText("");
        setMengeFieldRed(false);

        currentLagerItems.clear();
        lagerAdapter.notifyDataSetChanged();

        lockNachFocus(false);
        lockArtNrFocus(false);

        spinnerPopupOpen = false;
        awaitingSpinnerPick = false;

        inputField.requestFocus();
        showKeyboardHard(inputField);
    }

    // ===================== 1) GET /artikel =====================

    void makeArtikelRequest(String textToSend) {
        HttpURLConnection connection = null;

        try {
            String encoded = URLEncoder.encode(textToSend, StandardCharsets.UTF_8.name());
            String targetUrl = "http://10.0.20.26:8080/artikel?text=" + encoded;

            Log.i(TAG, "Connecting to: " + targetUrl);

            URL url = new URL(targetUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);

            int responseCode = connection.getResponseCode();

            InputStream stream = (responseCode >= 400)
                    ? connection.getErrorStream()
                    : connection.getInputStream();

            String responseBody = readAll(stream);
            Log.i(TAG, "HTTP " + responseCode + " body: " + responseBody);

            if (responseCode >= 400) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Server-Fehler HTTP " + responseCode, Toast.LENGTH_LONG).show();
                    setArtikelFieldRed(true);
                });
                return;
            }

            JSONObject obj = new JSONObject(responseBody);

            String benennung = obj.optString("benennung", "");
            String me = obj.optString("me", "");

            List<LagerItem> newItems = new ArrayList<>();
            JSONArray lb = obj.optJSONArray("lagerBestand");
            if (lb != null) {
                for (int i = 0; i < lb.length(); i++) {
                    JSONObject entry = lb.optJSONObject(i);
                    if (entry == null) continue;

                    String lagernr = entry.optString("lagernr", "").trim();
                    double bestand = entry.optDouble("bestand", 0.0);

                    if (!lagernr.isEmpty()) newItems.add(new LagerItem(lagernr, bestand));
                }
            }

            runOnUiThread(() -> {
                nameField.setText(benennung);
                meField.setText(me);

                bestandField.setText("");
                setNachFieldRed(false);

                mengeField.setText("");
                setMengeFieldRed(false);

                suppressNextJump = true;
                currentLagerItems.clear();
                currentLagerItems.addAll(newItems);
                lagerAdapter.notifyDataSetChanged();

                if (!currentLagerItems.isEmpty()) {
                    lagerSpinner.setSelection(0, false);
                }
                suppressNextJump = false;

                if (currentLagerItems.isEmpty()) {
                    Toast.makeText(this, "Keine Lagerplätze verfügbar", Toast.LENGTH_SHORT).show();
                    setArtikelFieldRed(false);
                } else if (currentLagerItems.size() == 1) {
                    lockNachFocus(false);
                    lockArtNrFocus(false);
                    showKeyboardHard(bestandField);
                } else {
                    openSpinnerAutomatically();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "HTTP/JSON Exception", e);
            runOnUiThread(() -> {
                Toast.makeText(this, "Ungültig: " + e.getMessage(), Toast.LENGTH_LONG).show();
                setArtikelFieldRed(true);
            });
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void openSpinnerAutomatically() {
        // We still want the "awaiting pick" behavior for auto-open
        onSpinnerPopupOpening();
        lagerSpinner.post(() -> lagerSpinner.performClick());
    }

    // ===================== 2) POST /lagernr =====================

    private void postLagernr(String nachText) {
        HttpURLConnection connection = null;

        try {
            String targetUrl = "http://10.0.20.26:8080/lagernr";
            URL url = new URL(targetUrl);

            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            JSONObject body = new JSONObject();
            body.put("lagernr", nachText);

            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(out);
            }

            int responseCode = connection.getResponseCode();

            InputStream stream = (responseCode >= 400)
                    ? connection.getErrorStream()
                    : connection.getInputStream();

            String responseBody = readAll(stream);
            Log.i(TAG, "POST /lagernr HTTP " + responseCode + " body: " + responseBody);

            runOnUiThread(() -> {
                if (responseCode == 200) {
                    bestandField.setText(responseBody != null ? responseBody : "");
                    setNachFieldRed(false);
                    lastNachSent = bestandField.getText().toString().trim();
                } else if (responseCode == 404) {
                    setNachFieldRed(true);
                } else if (responseCode >= 400) {
                    setNachFieldRed(true);
                    Toast.makeText(this, "lagernr Fehler HTTP " + responseCode, Toast.LENGTH_SHORT).show();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "POST /lagernr Exception", e);
            runOnUiThread(() -> {
                setNachFieldRed(true);
                Toast.makeText(this, "lagernr POST Fehler: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    // ===================== 3) POST /umbuchung =====================

    private void sendUmbuchungPost() {
        HttpURLConnection connection = null;
        setPlayEnabled(false);

        final String artnrTry = inputField.getText().toString().trim();

        LagerItem selected = (LagerItem) lagerSpinner.getSelectedItem();
        final String vonTry = (selected != null) ? selected.lagernr.trim() : "";
        final double vonBestand = (selected != null) ? selected.bestand : 0.0;

        final String nachTry = bestandField.getText().toString().trim();

        String mengeRaw = mengeField.getText().toString().trim();
        mengeRaw = mengeRaw.replace(',', '.');
        final String mengeTry = mengeRaw;

        final String timeTry = timeFmt.format(new Date());

        try {
            if (artnrTry.isEmpty()) {
                setLastTry(false, artnrTry, vonTry, nachTry, mengeTry, timeTry);
                runOnUiThread(() -> Toast.makeText(this, "Artikel-Nr fehlt", Toast.LENGTH_SHORT).show());
                return;
            }
            if (vonTry.isEmpty()) {
                setLastTry(false, artnrTry, vonTry, nachTry, mengeTry, timeTry);
                runOnUiThread(() -> Toast.makeText(this, "Von Lager fehlt", Toast.LENGTH_SHORT).show());
                return;
            }
            if (nachTry.isEmpty()) {
                setLastTry(false, artnrTry, vonTry, nachTry, mengeTry, timeTry);
                runOnUiThread(() -> Toast.makeText(this, "Nach Lager fehlt", Toast.LENGTH_SHORT).show());
                return;
            }
            if (mengeRaw.isEmpty()) {
                setLastTry(false, artnrTry, vonTry, nachTry, mengeTry, timeTry);
                runOnUiThread(() -> Toast.makeText(this, "Menge fehlt", Toast.LENGTH_SHORT).show());
                return;
            }

            double menge;
            try {
                menge = Double.parseDouble(mengeRaw);
            } catch (Exception e) {
                setLastTry(false, artnrTry, vonTry, nachTry, mengeTry, timeTry);
                runOnUiThread(() -> {
                    setMengeFieldRed(true);
                    Toast.makeText(this, "Menge ungültig", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            if (menge > vonBestand) {
                setLastTry(false, artnrTry, vonTry, nachTry, mengeTry, timeTry);
                runOnUiThread(() -> {
                    setMengeFieldRed(true);
                    Toast.makeText(this, "Menge ist größer als Bestand", Toast.LENGTH_SHORT).show();
                });
                return;
            } else {
                runOnUiThread(() -> setMengeFieldRed(false));
            }

            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

            JSONObject body = new JSONObject();
            body.put("artnr", artnrTry);
            body.put("von", vonTry);
            body.put("nach", nachTry);
            body.put("menge", menge);
            body.put("deviceID", androidId);

            String targetUrl = "http://10.0.20.26:8080/umbuchung";
            URL url = new URL(targetUrl);

            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(out);
            }

            int responseCode = connection.getResponseCode();

            InputStream stream = (responseCode >= 400)
                    ? connection.getErrorStream()
                    : connection.getInputStream();

            String responseBody = readAll(stream);
            Log.i(TAG, "POST /umbuchung HTTP " + responseCode + " body: " + responseBody);

            setLastTry(responseCode < 400, artnrTry, vonTry, nachTry, mengeTry, timeTry);

            runOnUiThread(() -> {
                if (responseCode >= 400) {
                    Toast.makeText(this, "Umbuchung FEHLER (HTTP " + responseCode + ")", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Umbuchung OK", Toast.LENGTH_SHORT).show();
                    clearAllAndFocus();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "POST Exception", e);
            setLastTry(false, artnrTry, vonTry, nachTry, mengeTry, timeTry);
            runOnUiThread(() ->
                    Toast.makeText(this, "Umbuchung FEHLER: " + e.getMessage(), Toast.LENGTH_LONG).show()
            );
        } finally {
            if (connection != null) connection.disconnect();
            setPlayEnabled(true);
        }
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    // ----------- Helper Model for spinner -----------
    private class LagerItem {
        final String lagernr;
        final double bestand;

        LagerItem(String lagernr, double bestand) {
            this.lagernr = lagernr;
            this.bestand = bestand;
        }

        @Override
        public String toString() {
            return String.format("%-12s    %s", lagernr.trim(), df.format(bestand));
        }
    }
}
