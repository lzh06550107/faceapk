package com.punch.app.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.punch.app.R;
import com.punch.app.adapter.InteractionLogAdapter;
import com.punch.app.network.InteractionLogEntry;
import com.punch.app.network.InteractionLogStore;
import com.punch.app.network.InteractionLogger;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.LogDisplayFormatter;
import com.punch.app.utils.SessionManager;

import java.util.ArrayList;
import java.util.List;

public class InteractionLogActivity extends AppCompatActivity implements InteractionLogStore.Listener {
    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private TextView tvSummary;
    private InteractionLogAdapter adapter;
    private RadioGroup filterGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_interaction_log);

        recyclerView = findViewById(R.id.rv_logs);
        tvEmpty = findViewById(R.id.tv_empty_logs);
        tvSummary = findViewById(R.id.tv_log_summary);
        filterGroup = findViewById(R.id.rg_log_filters);
        MaterialButton btnBack = findViewById(R.id.btn_back_logs);
        MaterialButton btnCopy = findViewById(R.id.btn_copy_logs);
        MaterialButton btnClear = findViewById(R.id.btn_clear_logs);

        adapter = new InteractionLogAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        btnBack.setOnClickListener(v -> finish());
        btnCopy.setOnClickListener(v -> copyLogs());
        btnClear.setOnClickListener(v -> confirmClearLogs());
        filterGroup.setOnCheckedChangeListener((group, checkedId) -> renderLogs());

        renderLogs();
    }

    @Override
    protected void onStart() {
        super.onStart();
        InteractionLogStore store = InteractionLogStore.get();
        if (store != null) {
            store.registerListener(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        KioskManager.enterIfPossible(this);
    }

    @Override
    protected void onStop() {
        InteractionLogStore store = InteractionLogStore.get();
        if (store != null) {
            store.unregisterListener(this);
        }
        super.onStop();
    }

    @Override
    public void onLogsChanged() {
        renderLogs();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && KioskManager.shouldBlockSystemKey(event.getKeyCode())) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            KioskManager.restoreAppTaskSoon(this);
        }
    }

    @Override
    public void onBackPressed() {
        if (SessionManager.get().isKioskEnabled()) {
            return;
        }
        super.onBackPressed();
    }

    private void renderLogs() {
        InteractionLogStore store = InteractionLogStore.get();
        List<InteractionLogEntry> allEntries = store == null ? java.util.Collections.emptyList() : store.snapshot();
        List<InteractionLogEntry> entries = filterEntries(allEntries);
        adapter.submit(entries);
        boolean empty = entries.isEmpty();
        int failedCount = 0;
        for (InteractionLogEntry entry : entries) {
            if (entry != null && !entry.success) {
                failedCount++;
            }
        }
        tvSummary.setText("当前筛选 " + entries.size() + " 条，失败 " + failedCount + " 条");
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void copyLogs() {
        InteractionLogStore store = InteractionLogStore.get();
        List<InteractionLogEntry> entries = store == null ? java.util.Collections.emptyList() : store.snapshot();
        if (entries.isEmpty()) {
            Toast.makeText(this, "暂无可复制日志", Toast.LENGTH_SHORT).show();
            return;
        }
        String exportText = LogDisplayFormatter.buildExportText(entries);
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("interaction-logs", exportText));
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClearLogs() {
        new AlertDialog.Builder(this)
                .setTitle("清空日志")
                .setMessage("是否删除本地保存的全部交互日志？")
                .setPositiveButton("清空", (dialog, which) -> {
                    InteractionLogStore store = InteractionLogStore.get();
                    if (store != null) {
                        store.clear();
                    }
                    Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private List<InteractionLogEntry> filterEntries(List<InteractionLogEntry> allEntries) {
        if (allEntries == null || allEntries.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<InteractionLogEntry> filtered = new ArrayList<>();
        int checkedId = filterGroup.getCheckedRadioButtonId();
        for (InteractionLogEntry entry : allEntries) {
            if (entry == null) {
                continue;
            }
            if (checkedId == R.id.rb_filter_failed) {
                if (!entry.success) {
                    filtered.add(entry);
                }
                continue;
            }
            if (checkedId == R.id.rb_filter_heartbeat) {
                if (InteractionLogger.GROUP_HEARTBEAT.equals(entry.group)) {
                    filtered.add(entry);
                }
                continue;
            }
            if (checkedId == R.id.rb_filter_employee_sync) {
                if (InteractionLogger.GROUP_EMPLOYEE_SYNC.equals(entry.group)) {
                    filtered.add(entry);
                }
                continue;
            }
            if (checkedId == R.id.rb_filter_event_result) {
                if (InteractionLogger.GROUP_EVENT_RESULT.equals(entry.group)) {
                    filtered.add(entry);
                }
                continue;
            }
            if (checkedId == R.id.rb_filter_business) {
                if (InteractionLogger.CATEGORY_BUSINESS.equals(entry.category)) {
                    filtered.add(entry);
                }
                continue;
            }
            filtered.add(entry);
        }
        return filtered;
    }
}
