package com.sinux.pocketboard.preferences;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.sinux.pocketboard.R;
import com.sinux.pocketboard.input.mapping.SymPadMappingManager;

public class SympadPreferencesFragment extends PreferenceFragmentCompat {

    private ActivityResultLauncher<Intent> saveJsonLauncher;
    private ActivityResultLauncher<Intent> loadJsonLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        saveJsonLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && getContext() != null) {
                        // Load mapping from local file and write to external
                        SymPadMappingManager.exportToFile(getContext(), result.getData().getData());
                    }
                }
        );

        loadJsonLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && getContext() != null) {
                        // Load mapping from external file and save to local
                        SymPadMappingManager.importFromFile(getContext(), result.getData().getData());

                        Preference defaultsPref = findPreference(getString(R.string.ime_sympad_defaults_key));
                        if (defaultsPref != null)
                            defaultsPref.setEnabled(true);
                    }
                }
        );
    }

    @Override
    public void onResume() {
        super.onResume();

        Preference defaultsPref = findPreference(getString(R.string.ime_sympad_defaults_key));
        if (defaultsPref != null) {
            defaultsPref.setEnabled(SymPadMappingManager.hasCustomMapping(requireContext()));
        }
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setStorageDeviceProtected();
        setPreferencesFromResource(R.xml.sympad_preferences, rootKey);

        Preference loadPref = findPreference(getString(R.string.ime_sympad_json_import_key));
        if (loadPref != null) {
            loadPref.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                loadJsonLauncher.launch(intent);
                return true;
            });
        }

        Preference savePref = findPreference(getString(R.string.ime_sympad_json_export_key));
        if (savePref != null) {
            savePref.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, SymPadMappingManager.FILE_NAME);
                saveJsonLauncher.launch(intent);
                return true;
            });
        }

        Preference defaultsPref = findPreference(getString(R.string.ime_sympad_defaults_key));
        if (defaultsPref != null) {
            defaultsPref.setEnabled(SymPadMappingManager.hasCustomMapping(requireContext()));
            defaultsPref.setOnPreferenceClickListener(preference -> {
                new MaterialAlertDialogBuilder(requireContext())
                        .setMessage(getString(R.string.ime_sympad_defaults_message))
                        .setPositiveButton(getString(android.R.string.ok), (dialog, which) -> {
                            SymPadMappingManager.resetToDefaults(requireContext());
                            defaultsPref.setEnabled(false);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            });
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getActivity() != null) {
            getActivity().setTitle(getPreferenceScreen().getTitle());
        }
    }
}
