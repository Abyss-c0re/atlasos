package com.sinux.pocketboard.preferences;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.sinux.pocketboard.R;

import java.util.List;

public class SympadEditorKeysListSpinnerAdapter extends ArrayAdapter<SympadEditorItemAdapter.KeySpinnerItem> {

    private final List<SympadEditorItemAdapter.KeySpinnerItem> keys;
    private final Runnable onUpdate;

    public SympadEditorKeysListSpinnerAdapter(Context context, List<SympadEditorItemAdapter.KeySpinnerItem> keys, Runnable onUpdate) {
        super(context, R.layout.sympad_editor_list_item_view, keys);
        this.keys = keys;
        this.onUpdate = onUpdate;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.sympad_editor_list_item_view, parent, false);
        }

        TextView textView = convertView.findViewById(R.id.sympadEditorKeyListKeyName);
        ImageButton delBtn = convertView.findViewById(R.id.sympadEditorKeyListKeyDelete);

        String keyName = keys.get(position).toString();
        textView.setText(keyName);

        delBtn.setOnClickListener(v -> {
            keys.remove(position);
            notifyDataSetChanged();
            if (onUpdate != null)
                onUpdate.run();
        });

        return convertView;
    }
}