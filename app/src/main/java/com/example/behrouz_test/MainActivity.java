package com.example.behrouz_test;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HTTP_CALL";

    private EditText inputField, nameField, lagerField, bestandField, meField, mengeField;

    // Bottom panel for "von" options
    private LinearLayout vonOptionsPanel;
    private GridLayout vonGrid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputField = findViewById(R.id.inputField);
        nameField = findViewById(R.id.nameField);
        lagerField = findViewById(R.id.lagerField);       // "von"
        bestandField = findViewById(R.id.bestandField);   // "nach"
        meField = findViewById(R.id.meField);
        mengeField = findViewById(R.id.menge);

        vonOptionsPanel = findViewById(R.id.vonOptionsPanel);
        vonGrid = findViewById(R.id.vonGrid);

        // Keep keyboard hidden initially
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        // Ensure "von" does not bring keyboard even if user taps it
        lagerField.setShowSoftInputOnFocus(false);

        // When "von" gains focus, show options panel and populate buttons
        lagerField.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showVonOptions(getVonOptionsForCurrentArticle());
            } else {
                hideVonOptions();
            }
        });

        // Also show the options when user taps "von"
        lagerField.setOnClickListener(v -> {
            lagerField.requestFocus();
            showVonOptions(getVonOptionsForCurrentArticle());
        });

        // Scan / enter Artikel-Nr, then HTTP call
        inputField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                String inputText = inputField.getText().toString().trim();
                new Thread(() -> makeHttpRequest(inputText)).start();
                return true;
            }
            return false;
        });

        inputField.requestFocus();

        Button clearButton = findViewById(R.id.clearButton);
        clearButton.setOnClickListener(v -> {
            inputField.setText("");
            nameField.setText("");
            lagerField.setText("");
            bestandField.setText("");
            meField.setText("");
            mengeField.setText("");

            hideVonOptions();
            inputField.requestFocus();
        });
    }

    /**
     * This currently returns demo values.
     * Later you will return a dynamic list based on Artikel-Nr (or HTTP response).
     */
    private List<String> getVonOptionsForCurrentArticle() {
        // Change 5 -> 10 later if you want 10 items
        return Arrays.asList("A1", "A2", "A3", "B1", "B2");
    }

    private void showVonOptions(List<String> options) {
        // Make bottom half visible
        vonOptionsPanel.setVisibility(View.VISIBLE);

        // Clear old buttons
        vonGrid.removeAllViews();

        // Grid settings: 1 column (vertical list) by default from XML.
        // If you want 2 columns later, set columnCount=2 in XML and adjust sizes.

        int buttonHeightPx = dpToPx(56);

        for (String option : options) {
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(option);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = GridLayout.LayoutParams.MATCH_PARENT;
            lp.height = buttonHeightPx;
            lp.setMargins(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));
            b.setLayoutParams(lp);

            b.setOnClickListener(v -> {
                // Fill "von"
                lagerField.setText(option);

                // Hide panel after selection (so user sees the form again)
                hideVonOptions();

                // Move to next step: focus "nach" (or Menge later)
                // Your "nach" is currently not focusable in XML; when you enable it,
                // you can requestFocus here. For now, just keep it as a placeholder.
                // bestandField.requestFocus();
            });

            vonGrid.addView(b);
        }
    }

    private void hideVonOptions() {
        vonOptionsPanel.setVisibility(View.GONE);
        vonGrid.removeAllViews();
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void makeHttpRequest(String numberToSend) {
        try {
            String targetUrl = "http://10.0.20.26:8080/get_art";
            Log.i(TAG, "Trying to connect to " + targetUrl);

            URL url = new URL(targetUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");

            try (OutputStream os = connection.getOutputStream()) {
                os.write(numberToSend.getBytes(StandardCharsets.UTF_8));
            }

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(connection.getInputStream())
            );
            String inputLine;
            StringBuilder response = new StringBuilder();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            String responseBody = response.toString();
            String[] parts = responseBody.split("\\|");

            String name = parts[0];
            String lager  = parts[1];
            String bestand = "" + (Double.parseDouble(parts[2]) + Double.parseDouble(parts[3]) + Double.parseDouble(parts[4]));
            String me = parts[5];

            runOnUiThread(() -> {
                nameField.setText(name);
                lagerField.setText(lager);       // this is currently your server value; you can keep or clear it
                bestandField.setText(bestand);
                meField.setText(me);

                // USER-FRIENDLY FLOW:
                // After scan, immediately focus "von" so the option panel opens
                lagerField.requestFocus(); // triggers showVonOptions via focus listener
            });

        } catch (Exception e) {
            Log.e(TAG, "Exception occurred", e);
            runOnUiThread(() ->
                    Toast.makeText(this, "Ungültig: " + e.getMessage(), Toast.LENGTH_LONG).show()
            );
        }
    }
}
