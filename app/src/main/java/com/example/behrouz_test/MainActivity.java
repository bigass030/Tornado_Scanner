package com.example.behrouz_test;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HTTP_CALL";

    private EditText inputField, nameField, bestandField, meField, mengeField;
    private AutoCompleteTextView lagerField; // "von"

    private final List<String> currentLagerOptions = new ArrayList<>();
    private ArrayAdapter<String> lagerAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputField = findViewById(R.id.inputField);
        nameField = findViewById(R.id.nameField);
        lagerField = findViewById(R.id.lagerField);
        bestandField = findViewById(R.id.bestandField); // "nach"
        meField = findViewById(R.id.meField);
        mengeField = findViewById(R.id.menge);          // "Menge"

        // Start with keyboard hidden; we open it only on input fields
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        lagerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, currentLagerOptions);
        lagerField.setAdapter(lagerAdapter);

        // No typing for "von"
        lagerField.setShowSoftInputOnFocus(false);

        lagerField.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showVonDropdownIfPossible();
        });
        lagerField.setOnClickListener(v -> showVonDropdownIfPossible());

        // After selecting "von" -> go to "nach" and open keyboard
        lagerField.setOnItemClickListener((parent, view, position, id) -> {
            String selected = (String) parent.getItemAtPosition(position);
            lagerField.setText(selected, false);

            bestandField.requestFocus();
            showKeyboard(bestandField);
        });

        // When user presses "Next" on "nach" -> jump to Menge and show numeric keyboard
        bestandField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                mengeField.requestFocus();
                showKeyboard(mengeField);
                return true;
            }
            return false;
        });

        // When Menge gains focus -> ensure keyboard is shown (numeric keyboard is controlled by XML inputType)
        mengeField.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showKeyboard(mengeField);
        });

        // Scan Artikel-Nr -> HTTP call
        inputField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                String inputText = inputField.getText().toString().trim();
                if (inputText.isEmpty()) {
                    Toast.makeText(this, "Bitte Artikel-Nr scannen", Toast.LENGTH_SHORT).show();
                    return true;
                }
                new Thread(() -> makeHttpRequest(inputText)).start();
                return true;
            }
            return false;
        });

        inputField.requestFocus();

        Button clearButton = findViewById(R.id.clearButton);
        clearButton.setOnClickListener(v -> clearAllAndFocus());
    }

    private void clearAllAndFocus() {
        inputField.setText("");
        nameField.setText("");
        lagerField.setText("");
        bestandField.setText("");
        meField.setText("");
        mengeField.setText("");

        currentLagerOptions.clear();
        lagerAdapter.notifyDataSetChanged();

        inputField.requestFocus();
    }

    private void showVonDropdownIfPossible() {
        if (currentLagerOptions.isEmpty()) {
            Toast.makeText(this, "Keine Lagerplätze verfügbar", Toast.LENGTH_SHORT).show();
            return;
        }
        lagerField.setThreshold(0);
        lagerField.showDropDown();
    }

    private void showKeyboard(EditText field) {
        field.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void makeHttpRequest(String textToSend) {
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
                runOnUiThread(() ->
                        Toast.makeText(this, "Server-Fehler HTTP " + responseCode, Toast.LENGTH_LONG).show()
                );
                return;
            }

            JSONObject obj = new JSONObject(responseBody);

            String benennung = obj.optString("benennung", "");
            String me = obj.optString("me", "");

            List<String> newOptions = new ArrayList<>();
            JSONArray lagers = obj.optJSONArray("lagernr");
            if (lagers != null) {
                for (int i = 0; i < lagers.length(); i++) {
                    String val = lagers.optString(i, "").trim();
                    if (!val.isEmpty()) newOptions.add(val);
                }
            }

            runOnUiThread(() -> {
                nameField.setText(benennung);
                meField.setText(me);

                currentLagerOptions.clear();
                currentLagerOptions.addAll(newOptions);
                lagerAdapter.notifyDataSetChanged();

                // reset user inputs
                lagerField.setText("");
                bestandField.setText("");
                mengeField.setText("");

                // go to "von" and open dropdown
                lagerField.requestFocus();
                showVonDropdownIfPossible();
            });

        } catch (Exception e) {
            Log.e(TAG, "HTTP/JSON Exception", e);
            runOnUiThread(() ->
                    Toast.makeText(this, "Ungültig: " + e.getMessage(), Toast.LENGTH_LONG).show()
            );
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) sb.append(line);
        in.close();
        return sb.toString();
    }
}
