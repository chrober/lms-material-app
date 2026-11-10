/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

import java.util.List;

/**
 * The rows of the Android Auto tab order dialog: drag a row by its handle (or by a long press) to move it.
 * Screen reader users get "Move up" / "Move down" actions on each row instead.
 */
public class TabOrderAdapter extends RecyclerView.Adapter<TabOrderAdapter.Row> {
    public interface Labels {
        String of(String tab);
    }

    static class Row extends RecyclerView.ViewHolder {
        final TextView label;
        final View handle;
        int moveUpAction = View.NO_ID;
        int moveDownAction = View.NO_ID;

        Row(View view) {
            super(view);
            label = view.findViewById(R.id.tab_order_label);
            handle = view.findViewById(R.id.tab_order_handle);
        }
    }

    private final List<String> order;
    private final Labels labels;
    private final ItemTouchHelper touchHelper;
    private RecyclerView view;

    TabOrderAdapter(List<String> order, Labels labels) {
        this.order = order;
        this.labels = labels;
        touchHelper = new ItemTouchHelper(new DragCallback());
    }

    void attachTo(RecyclerView recyclerView) {
        view = recyclerView;
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.setAdapter(this);
        touchHelper.attachToRecyclerView(recyclerView);
    }

    @NonNull
    @Override
    public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Row(LayoutInflater.from(parent.getContext()).inflate(R.layout.tab_order_row, parent, false));
    }

    @SuppressLint("ClickableViewAccessibility") // Screen readers use the row's accessibility actions
    @Override
    public void onBindViewHolder(@NonNull Row row, int position) {
        row.label.setText((position + 1) + ". " + labels.of(order.get(position)));
        row.handle.setOnTouchListener((v, event) -> {
            if (MotionEvent.ACTION_DOWN==event.getActionMasked()) {
                touchHelper.startDrag(row);
            }
            return false;
        });

        View item = row.itemView;
        if (View.NO_ID!=row.moveUpAction) {
            ViewCompat.removeAccessibilityAction(item, row.moveUpAction);
            row.moveUpAction = View.NO_ID;
        }
        if (View.NO_ID!=row.moveDownAction) {
            ViewCompat.removeAccessibilityAction(item, row.moveDownAction);
            row.moveDownAction = View.NO_ID;
        }
        if (position > 0) {
            row.moveUpAction = ViewCompat.addAccessibilityAction(item, item.getContext().getString(R.string.move_up), (v, args) -> {
                int from = row.getBindingAdapterPosition();
                return from > 0 && move(from, from - 1, true);
            });
        }
        if (position < order.size() - 1) {
            row.moveDownAction = ViewCompat.addAccessibilityAction(item, item.getContext().getString(R.string.move_down), (v, args) -> {
                int from = row.getBindingAdapterPosition();
                return from!=RecyclerView.NO_POSITION && from < order.size() - 1 && move(from, from + 1, true);
            });
        }
    }

    @Override
    public int getItemCount() {
        return order.size();
    }

    private boolean move(int from, int to, boolean renumber) {
        AutoTabOrder.move(order, from, to);
        notifyItemMoved(from, to);
        if (renumber) {
            renumber();
        }
        return true;
    }

    // The position numbers in the labels, and which rows can move up or down
    private void renumber() {
        notifyItemRangeChanged(0, order.size());
    }

    private class DragCallback extends ItemTouchHelper.SimpleCallback {
        DragCallback() {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder dragged, @NonNull RecyclerView.ViewHolder target) {
            int from = dragged.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            if (RecyclerView.NO_POSITION==from || RecyclerView.NO_POSITION==to) {
                return false;
            }
            // Renumbered once the row is dropped: rebinding the dragged row now would disturb the drag
            return move(from, to, false);
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            super.onSelectedChanged(viewHolder, actionState);
            if (ItemTouchHelper.ACTION_STATE_DRAG==actionState && null!=viewHolder) {
                View item = viewHolder.itemView;
                item.setBackgroundColor(MaterialColors.getColor(item, com.google.android.material.R.attr.colorSurfaceContainerHighest));
            }
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            viewHolder.itemView.setBackground(null);
            if (null!=view) {
                view.post(TabOrderAdapter.this::renumber);
            }
        }
    }
}
