package com.punch.app.fragment;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.punch.app.R;
import com.punch.app.adapter.ClockStatisticsAdapter;
import com.punch.app.adapter.PunchRecordAdapter;
import com.punch.app.db.DatabaseHelper;
import com.punch.app.model.PunchRecord;
import com.punch.app.network.ApiResult;
import com.punch.app.network.ApiService;
import com.punch.app.network.dto.PunchDto;
import com.punch.app.utils.SessionManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecordsFragment extends Fragment {
    private static final int PAGE_SIZE = 20;
    private static final String FREE_PUNCH_OPTION_LABEL = "\u81ea\u7531\u6253\u5361";
    private static final String PUNCH_TYPE_FREE = "free";
    private static final String UNSCHEDULED_PUNCH_OPTION_LABEL = "\u672a\u914d\u7f6e\u73ed\u6b21";

    private ToggleButton toggleMode;
    private TextView tvDate;
    private TextView tvTotalCount;
    private TextView tvTotalLabel;
    private TextView tvClockedCount;
    private TextView tvClockedLabel;
    private TextView tvUnclockedCount;
    private TextView tvUnclockedLabel;
    private TextView tvSpecialCount;
    private TextView tvSpecialLabel;
    private View viewSpecialGap;
    private View layoutSpecialCard;
    private Spinner spinnerPunchIndex;
    private Spinner spinnerStatus;
    private RecyclerView recyclerView;

    private PunchRecordAdapter localAdapter;
    private ClockStatisticsAdapter onlineAdapter;
    private ArrayAdapter<String> punchOptionAdapter;
    private ArrayAdapter<String> statusAdapter;
    private final List<PunchOption> punchOptions = new ArrayList<>();
    private final List<StatusOption> statusOptions = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private boolean suppressSelectionCallback;
    private boolean loading;
    private boolean onlineMode;
    private boolean preferOnlineMode;
    private boolean hasMore;
    private int currentPage;
    private PunchOption selectedPunchOption;
    private StatusOption selectedStatusOption;
    private String selectedDate;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_records, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        toggleMode = view.findViewById(R.id.toggle_mode);
        tvDate = view.findViewById(R.id.tv_date);
        tvTotalCount = view.findViewById(R.id.tv_total_count);
        tvTotalLabel = view.findViewById(R.id.tv_total_label);
        tvClockedCount = view.findViewById(R.id.tv_clocked_count);
        tvClockedLabel = view.findViewById(R.id.tv_clocked_label);
        tvUnclockedCount = view.findViewById(R.id.tv_unclocked_count);
        tvUnclockedLabel = view.findViewById(R.id.tv_unclocked_label);
        tvSpecialCount = view.findViewById(R.id.tv_special_count);
        tvSpecialLabel = view.findViewById(R.id.tv_special_label);
        viewSpecialGap = view.findViewById(R.id.view_special_gap);
        layoutSpecialCard = view.findViewById(R.id.layout_special_card);
        spinnerPunchIndex = view.findViewById(R.id.spinner_punch_index);
        spinnerStatus = view.findViewById(R.id.spinner_status);
        recyclerView = view.findViewById(R.id.recycler_view);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        localAdapter = new PunchRecordAdapter(requireContext(), new ArrayList<>());
        onlineAdapter = new ClockStatisticsAdapter();

        selectedDate = buildToday();
        tvDate.setText(selectedDate);
        tvDate.setOnClickListener(v -> showDatePicker());

        preferOnlineMode = SessionManager.get().isTokenValid();
        toggleMode.setChecked(preferOnlineMode);
        toggleMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preferOnlineMode = isChecked;
            rebuildPunchOptions();
            refresh();
        });

        punchOptionAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                new ArrayList<>()
        );
        spinnerPunchIndex.setAdapter(punchOptionAdapter);
        spinnerPunchIndex.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View itemView, int position, long id) {
                if (suppressSelectionCallback || position < 0 || position >= punchOptions.size()) {
                    return;
                }
                selectedPunchOption = punchOptions.get(position);
                refresh();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        statusAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                new ArrayList<>()
        );
        spinnerStatus.setAdapter(statusAdapter);
        spinnerStatus.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View itemView, int position, long id) {
                if (suppressSelectionCallback || position < 0 || position >= statusOptions.size()) {
                    return;
                }
                selectedStatusOption = statusOptions.get(position);
                refresh();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy <= 0 || !onlineMode || loading || !hasMore) {
                    return;
                }
                RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
                if (!(layoutManager instanceof LinearLayoutManager)) {
                    return;
                }
                LinearLayoutManager linear = (LinearLayoutManager) layoutManager;
                int lastVisible = linear.findLastVisibleItemPosition();
                if (lastVisible >= Math.max(0, onlineAdapter.getItemCount() - 4)) {
                    loadOnlinePage(false);
                }
            }
        });

        rebuildPunchOptions();
        rebuildStatusOptions();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuildPunchOptions();
        refresh();
    }

    public void refresh() {
        if (!isAdded() || loading) {
            return;
        }
        if (preferOnlineMode) {
            applyMode(true);
            loadOnlinePage(true);
        } else {
            applyMode(false);
            loadOfflineRecords();
        }
    }

    private void showDatePicker() {
        Calendar calendar = Calendar.getInstance();
        try {
            String[] parts = selectedDate.split("-");
            if (parts.length == 3) {
                calendar.set(Calendar.YEAR, Integer.parseInt(parts[0]));
                calendar.set(Calendar.MONTH, Integer.parseInt(parts[1]) - 1);
                calendar.set(Calendar.DAY_OF_MONTH, Integer.parseInt(parts[2]));
            }
        } catch (Exception ignored) {
        }

        new DatePickerDialog(
                requireContext(),
                (view, year, month, dayOfMonth) -> {
                    selectedDate = String.format(Locale.getDefault(),
                            "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                    tvDate.setText(selectedDate);
                    refresh();
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void rebuildPunchOptions() {
        if (spinnerPunchIndex == null || punchOptionAdapter == null) {
            return;
        }

        String currentLabel = selectedPunchOption != null ? selectedPunchOption.label : null;
        punchOptions.clear();
        if (!preferOnlineMode) {
            punchOptions.add(new PunchOption(FREE_PUNCH_OPTION_LABEL, 0, true));
        }

        int clockIndex = 1;
        for (String timeRange : SessionManager.get().getCurrentTeamTimeRanges()) {
            if (timeRange == null) {
                continue;
            }
            String range = timeRange.trim();
            if (range.isEmpty()) {
                continue;
            }
            punchOptions.add(new PunchOption(range + " \u4e0a\u73ed", clockIndex++));
            punchOptions.add(new PunchOption(range + " \u4e0b\u73ed", clockIndex++));
        }
        if (punchOptions.isEmpty()) {
            punchOptions.add(new PunchOption(UNSCHEDULED_PUNCH_OPTION_LABEL, 0, false));
        }

        List<String> labels = new ArrayList<>(punchOptions.size());
        int selectedIndex = 0;
        for (int i = 0; i < punchOptions.size(); i++) {
            PunchOption option = punchOptions.get(i);
            labels.add(option.label);
            if (option.label.equals(currentLabel)) {
                selectedIndex = i;
            }
        }

        suppressSelectionCallback = true;
        punchOptionAdapter.clear();
        punchOptionAdapter.addAll(labels);
        punchOptionAdapter.notifyDataSetChanged();
        spinnerPunchIndex.setSelection(selectedIndex, false);
        suppressSelectionCallback = false;
        selectedPunchOption = punchOptions.get(selectedIndex);
    }

    private void rebuildStatusOptions() {
        statusOptions.clear();
        statusOptions.add(new StatusOption("\u5168\u90e8\u72b6\u6001", 0));
        statusOptions.add(new StatusOption("\u5df2\u6253\u5361", 1));
        statusOptions.add(new StatusOption("\u672a\u6253\u5361", 2));
        statusOptions.add(new StatusOption("\u7279\u6b8a\u6253\u5361", 3));

        List<String> labels = new ArrayList<>(statusOptions.size());
        for (StatusOption option : statusOptions) {
            labels.add(option.label);
        }

        suppressSelectionCallback = true;
        statusAdapter.clear();
        statusAdapter.addAll(labels);
        statusAdapter.notifyDataSetChanged();
        spinnerStatus.setSelection(0, false);
        suppressSelectionCallback = false;
        selectedStatusOption = statusOptions.get(0);
    }

    private void applyMode(boolean useOnline) {
        onlineMode = useOnline;
        spinnerStatus.setVisibility(useOnline ? View.VISIBLE : View.GONE);
        viewSpecialGap.setVisibility(useOnline ? View.VISIBLE : View.GONE);
        layoutSpecialCard.setVisibility(useOnline ? View.VISIBLE : View.GONE);
        recyclerView.setAdapter(useOnline ? onlineAdapter : localAdapter);
    }

    private void loadOnlinePage(boolean reset) {
        if (!isAdded() || selectedPunchOption == null || selectedPunchOption.clockIndex <= 0) {
            uiHandler.post(() -> {
                applyMode(true);
                applyOnlineSummary(null);
                onlineAdapter.replace(new ArrayList<>());
            });
            return;
        }
        if (loading) {
            return;
        }

        loading = true;
        final int requestPage = reset ? 1 : currentPage + 1;
        final String date = selectedDate;
        final String lineCode = SessionManager.get().getLineCode();
        final int clockIndex = selectedPunchOption.clockIndex;
        final Integer clockStatus = selectedStatusOption != null ? selectedStatusOption.value : null;

        executor.execute(() -> {
            ApiResult<PunchDto.ClockStatisticsData> result = ApiService.fetchClockStatistics(
                    date,
                    lineCode,
                    clockIndex,
                    clockStatus,
                    requestPage,
                    PAGE_SIZE
            );
            uiHandler.post(() -> {
                loading = false;
                applyMode(true);
                if (!result.success || result.data == null) {
                    if (reset) {
                        applyOnlineSummary(null);
                        onlineAdapter.replace(new ArrayList<>());
                        currentPage = 0;
                        hasMore = false;
                    }
                    return;
                }

                PunchDto.ClockStatisticsData data = result.data;
                currentPage = requestPage;
                hasMore = (data.page * data.pageSize) < data.total;
                applyOnlineSummary(data);
                if (reset) {
                    onlineAdapter.replace(data.rows);
                } else {
                    onlineAdapter.append(data.rows);
                }
            });
        });
    }

    private void loadOfflineRecords() {
        final String date = selectedDate;
        final String lineCode = SessionManager.get().getLineCode();
        final PunchOption punchOption = selectedPunchOption;
        final String shiftName = punchOption != null ? punchOption.label : "";

        List<PunchRecord> allPunches = DatabaseHelper.get(requireContext())
                .getPunchRecordsByDate(date, lineCode);
        List<PunchRecord> filtered = new ArrayList<>();
        int syncedCount = 0;
        int pendingCount = 0;
        for (PunchRecord record : allPunches) {
            if (punchOption != null) {
                if (punchOption.freePunch) {
                    if (!PUNCH_TYPE_FREE.equals(record.punchType)) {
                        continue;
                    }
                } else if (!shiftName.equals(record.shiftName)) {
                    continue;
                }
            }
            filtered.add(record);
            if (record.isSynced == 1) {
                syncedCount++;
            } else {
                pendingCount++;
            }
        }

        final int finalSyncedCount = syncedCount;
        final int finalPendingCount = pendingCount;
        uiHandler.post(() -> {
            applyMode(false);
            tvTotalLabel.setText("\u672c\u5730\u8bb0\u5f55");
            tvClockedLabel.setText("\u5df2\u540c\u6b65");
            tvUnclockedLabel.setText("\u5f85\u540c\u6b65");
            tvTotalCount.setText(String.valueOf(filtered.size()));
            tvClockedCount.setText(String.valueOf(finalSyncedCount));
            tvUnclockedCount.setText(String.valueOf(finalPendingCount));
            localAdapter.update(filtered);
        });
    }

    private void applyOnlineSummary(@Nullable PunchDto.ClockStatisticsData data) {
        tvTotalLabel.setText("\u603b\u4eba\u6570");
        tvClockedLabel.setText("\u5df2\u6253\u5361");
        tvUnclockedLabel.setText("\u672a\u6253\u5361");
        tvSpecialLabel.setText("\u7279\u6b8a\u6253\u5361");
        if (data == null) {
            tvTotalCount.setText("0");
            tvClockedCount.setText("0");
            tvUnclockedCount.setText("0");
            tvSpecialCount.setText("0");
            return;
        }
        int totalCount = data.summary.total > 0 ? data.summary.total : data.total;
        tvTotalCount.setText(String.valueOf(totalCount));
        tvClockedCount.setText(String.valueOf(data.summary.clocked));
        tvUnclockedCount.setText(String.valueOf(data.summary.unclocked));
        tvSpecialCount.setText(String.valueOf(data.summary.special));
    }

    private String buildToday() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        recyclerView.setAdapter(null);
    }

    private static final class PunchOption {
        final String label;
        final int clockIndex;
        final boolean freePunch;

        PunchOption(String label, int clockIndex) {
            this(label, clockIndex, false);
        }

        PunchOption(String label, int clockIndex, boolean freePunch) {
            this.label = label;
            this.clockIndex = clockIndex;
            this.freePunch = freePunch;
        }
    }

    private static final class StatusOption {
        final String label;
        @Nullable
        final Integer value;

        StatusOption(String label, @Nullable Integer value) {
            this.label = label;
            this.value = value;
        }
    }
}
