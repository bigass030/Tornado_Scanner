package com.example.behrouz_test;

import android.provider.Settings;

import android.content.Context;

import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;

import android.os.Bundle;
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

    private EditText inputField;
    private EditText nameField;
    private EditText meField;
    private EditText bestandField; // NACH
    private EditText mengeField;

    private Spinner lagerSpinner;

    private TextView lastTryText;
    private TextView lastTryMark;

    private final List<LagerItem> currentLagerItems = new ArrayList<>();
    private ArrayAdapter<LagerItem> lagerAdapter;

    // Prevent “auto-selected first item” from triggering jump immediately after loading data
    private boolean suppressNextJump = false;

    // For pretty numbers in spinner label
    private final DecimalFormat df = new DecimalFormat("0.##");

    private String lastNachSent = "";

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

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

        // Clear red warning as soon as user starts correcting NACH
        bestandField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                setNachFieldRed(false);
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        // Clear red warning as soon as user starts correcting MENGE
        mengeField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                setMengeFieldRed(false);
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        bestandField.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String nachText = bestandField.getText().toString().trim();

                if (nachText.isEmpty()) return;

                if (nachText.equals(lastNachSent)) return;
                lastNachSent = nachText;

                new Thread(() -> postLagernr(nachText)).start();
            } else {
                showKeyboard(bestandField);
            }
        });

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        lagerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, currentLagerItems);
        lagerSpinner.setAdapter(lagerAdapter);

        lagerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                if (suppressNextJump) return;

                bestandField.requestFocus();
                showKeyboard(bestandField);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        bestandField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                mengeField.requestFocus();
                showKeyboard(mengeField);
                return true;
            }
            return false;
        });

        mengeField.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showKeyboard(mengeField);
        });

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

        // Clear red warning as soon as user starts correcting the Artikel-Nr
        inputField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                setArtikelFieldRed(false);
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        ImageButton clearIconButton = findViewById(R.id.clearIconButton);
        clearIconButton.setOnClickListener(v -> clearAllAndFocus());

        ImageButton playButton = findViewById(R.id.playButton);
        playButton.setOnClickListener(v -> new Thread(this::sendUmbuchungPost).start());

        inputField.requestFocus();
    }

    // ---------- last try UI ----------
    private void setLastTry(boolean success, String artnr, String von, String nach, String menge, String time) {
        final String text = artnr + "|" + von + "|" + nach + "|" + menge + "|" + time;

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


    // -------------------------------

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

        inputField.requestFocus();
        showKeyboard(inputField);
    }

    // ----------- 1) GET /artikel -----------
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
                    lagerSpinner.setSelection(0, false);
                    bestandField.requestFocus();
                    showKeyboard(bestandField);
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

    // ----------- 2) POST /lagernr -----------
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

    // ----------- 3) POST /umbuchung -----------
    private void sendUmbuchungPost() {
        HttpURLConnection connection = null;

        // Capture what the user tried + time (always)
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

            if (responseCode >= 400) {
                setLastTry(false, artnrTry, vonTry, nachTry, mengeTry, timeTry);
            } else {
                setLastTry(true, artnrTry, vonTry, nachTry, mengeTry, timeTry);
            }

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
        }
    }

    private void openSpinnerAutomatically() {
        lagerSpinner.post(() -> lagerSpinner.performClick());
    }

    private void showKeyboard(EditText editText) {
        editText.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
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
