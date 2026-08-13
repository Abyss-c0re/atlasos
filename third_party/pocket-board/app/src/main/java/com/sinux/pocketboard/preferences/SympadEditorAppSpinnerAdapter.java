package com.sinux.pocketboard.preferences;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.sinux.pocketboard.R;

import java.util.List;

public class SympadEditorAppSpinnerAdapter extends BaseAdapter {

    private final List<ResolveInfo> apps;
    private final PackageManager packageManager;
    private final LayoutInflater inflater;

    public SympadEditorAppSpinnerAdapter(Context context, List<ResolveInfo> apps, PackageManager pm) {
        this.apps = apps;
        this.packageManager = pm;
        this.inflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return apps.size();
    }

    @Override
    public Object getItem(int position) {
        return apps.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        return createViewFromResource(position, convertView, parent);
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        return createViewFromResource(position, convertView, parent);
    }

    private View createViewFromResource(int position, View convertView, ViewGroup parent) {
        TextView view = (TextView) (convertView != null ? convertView :
                inflater.inflate(R.layout.sympad_editor_dropdown_item_view, parent, false));

        ResolveInfo info = apps.get(position);

        if (info != null) {
            Drawable icon = info.loadIcon(packageManager);
            int size = (int) (24 * parent.getResources().getDisplayMetrics().density);
            icon.setBounds(0, 0, size, size);
            int padding = (int) (8 * parent.getResources().getDisplayMetrics().density);

            view.setCompoundDrawablesRelative(icon, null, null, null);
            view.setCompoundDrawablePadding(padding);
            view.setText(info.loadLabel(packageManager));
        } else {
            view.setText(parent.getResources().getString(R.string.ime_sympad_app_hint));
            view.setCompoundDrawablesRelative(null, null, null, null);
        }

        return view;
    }
}