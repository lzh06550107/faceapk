package com.punch.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.punch.app.R;
import com.punch.app.model.Employee;
import com.punch.app.utils.Constants;

import java.util.List;

public class UnsignedEmployeeAdapter extends RecyclerView.Adapter<UnsignedEmployeeAdapter.VH> {
    private List<Employee> items;

    public UnsignedEmployeeAdapter(List<Employee> items) {
        this.items = items;
    }

    public void update(List<Employee> data) {
        this.items = data;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_unsigned_employee, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Employee e = items.get(pos);
        h.tvName.setText(e.name);
        h.tvDept.setText(e.dept != null ? e.dept : e.assignedLineName);

        String statusLabel;
        int statusColor;
        switch (e.status != null ? e.status : "") {
            case Constants.STATUS_LEAVE:
                statusLabel = "请假";
                statusColor = 0xFFEF6C00;
                break;
            case Constants.STATUS_REST:
                statusLabel = "休息";
                statusColor = 0xFF5F6368;
                break;
            default:
                statusLabel = "未打卡";
                statusColor = 0xFFC62828;
                break;
        }
        h.tvStatus.setText(statusLabel);
        h.tvStatus.setTextColor(statusColor);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName;
        TextView tvDept;
        TextView tvStatus;

        VH(View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_name);
            tvDept = v.findViewById(R.id.tv_dept);
            tvStatus = v.findViewById(R.id.tv_status);
        }
    }
}
