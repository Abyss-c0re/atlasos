package com.sinux.pocketboard.preferences;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.sinux.pocketboard.R;

public class SympadEditorActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sympad_editor_layout);

        TabLayout tabLayout = findViewById(R.id.sympadEditorTabLayout);
        ViewPager2 pager = findViewById(R.id.sympadEditorPager);

        pager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                return new SympadEditorPage(position != 0);
            }

            @Override
            public int getItemCount() {
                return 2;
            }
        });

        new TabLayoutMediator(tabLayout, pager, (tab, position) -> {
            if (position == 0) {
                tab.setText(R.string.ime_sympad_short_press);
            } else {
                tab.setText(R.string.ime_sympad_long_press);
            }
        }).attach();
    }
}
