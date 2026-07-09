package com.punch.app.activity;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.punch.app.R;
import com.punch.app.adapter.LocalEmployeeDebugAdapter;
import com.punch.app.db.DatabaseHelper;
import com.punch.app.face.FaceFileManager;
import com.punch.app.face.FaceManager;
import com.punch.app.model.Employee;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalEmployeeDebugActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Employee> allEmployees = new ArrayList<>();

    private RecyclerView recyclerView;
    private LocalEmployeeDebugAdapter adapter;
    private TextView tvSummary;
    private TextView tvEmpty;
    private EditText etSearch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_local_employee_debug);

        recyclerView = findViewById(R.id.rv_local_employees);
        tvSummary = findViewById(R.id.tv_local_employee_summary);
        tvEmpty = findViewById(R.id.tv_empty_local_employees);
        etSearch = findViewById(R.id.et_search_local_employees);
        MaterialButton btnBack = findViewById(R.id.btn_back_local_employees);
        MaterialButton btnDeleteAll = findViewById(R.id.btn_refresh_local_employees);

        adapter = new LocalEmployeeDebugAdapter(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        btnBack.setOnClickListener(v -> finish());
        btnDeleteAll.setOnClickListener(v -> confirmDeleteAllEmployees());
        etSearch.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });

        loadEmployees();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void loadEmployees() {
        tvSummary.setText("正在加载本地人员列表...");
        executor.execute(() -> {
            List<Employee> employees = DatabaseHelper.get(this).getAllEmployeesForDebug();
            runOnUiThread(() -> {
                allEmployees.clear();
                allEmployees.addAll(employees);
                applyFilter();
            });
        });
    }

    private void confirmDeleteAllEmployees() {
        if (allEmployees.isEmpty()) {
            Toast.makeText(this, "当前没有可删除的本地人员", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("全部删除本地人员")
                .setMessage("确认删除当前设备上的全部本地人员和本地人脸图片吗？该操作不会通知平台。")
                .setPositiveButton("全部删除", (dialog, which) -> deleteAllEmployees())
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteAllEmployees() {
        tvSummary.setText("正在删除本地人员...");
        executor.execute(() -> {
            DatabaseHelper.get(this).clearAllEmployees();
            FaceFileManager.clearAllFaceImages(this);
            FaceManager.get().rebuildFaceLibrary(getApplicationContext());
            runOnUiThread(() -> {
                allEmployees.clear();
                applyFilter();
                Toast.makeText(this, "本地人员已全部删除", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void applyFilter() {
        String keyword = etSearch.getText() == null
                ? ""
                : etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Employee> filtered = new ArrayList<>();
        for (Employee employee : allEmployees) {
            if (employee == null) {
                continue;
            }
            if (keyword.isEmpty() || contains(employee.id, keyword) || contains(employee.name, keyword)) {
                filtered.add(employee);
            }
        }

        adapter.submit(filtered);
        renderSummary(filtered);
        boolean empty = filtered.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void renderSummary(List<Employee> filtered) {
        int total = allEmployees.size();
        int active = 0;
        int deleted = 0;
        int registered = 0;
        for (Employee employee : allEmployees) {
            if (employee == null) {
                continue;
            }
            if (employee.isDeleted == 1) {
                deleted++;
            } else {
                active++;
            }
            if (employee.faceRegistered == 1) {
                registered++;
            }
        }
        tvSummary.setText(String.format(
                Locale.getDefault(),
                "本地共 %d 人，启用 %d 人，已删除 %d 人，人脸已注册 %d 人，当前筛选 %d 人",
                total,
                active,
                deleted,
                registered,
                filtered.size()
        ));
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}
