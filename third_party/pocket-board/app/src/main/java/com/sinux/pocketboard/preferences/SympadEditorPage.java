package com.sinux.pocketboard.preferences;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.sinux.pocketboard.R;

public class SympadEditorPage extends Fragment {

    private final boolean isLongPress;

    public SympadEditorPage(boolean isLongPress) {
        this.isLongPress = isLongPress;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
        View view = inflater.inflate(R.layout.sympad_editor_page, container, false);
        RecyclerView rv = view.findViewById(R.id.sympadEditorKeysRecycler);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        rv.setAdapter(new SympadEditorItemAdapter(getContext(), isLongPress));
        return view;
    }
}
