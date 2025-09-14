package wseemann.media.fmpdemo.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import androidx.documentfile.provider.DocumentFile;

import java.util.List;

import wseemann.media.fmpdemo.R;

public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.FileViewHolder> {

    public interface OnFileSelectedListener {
        void onFileSelected(DocumentFile file);
    }

    private List<DocumentFile> mFiles;
    private OnFileSelectedListener mListener;

    public FileListAdapter(OnFileSelectedListener listener) {
        mListener = listener;
    }

    public void setFiles(List<DocumentFile> files) {
        mFiles = files;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        DocumentFile file = mFiles.get(position);
        holder.bind(file, mListener);
    }

    @Override
    public int getItemCount() {
        return mFiles != null ? mFiles.size() : 0;
    }

    static class FileViewHolder extends RecyclerView.ViewHolder {
        private TextView mFileName;

        FileViewHolder(View itemView) {
            super(itemView);
            mFileName = itemView.findViewById(R.id.file_name);
        }

        void bind(final DocumentFile file, final OnFileSelectedListener listener) {
            mFileName.setText(file.getName());
            itemView.setOnClickListener(v -> listener.onFileSelected(file));
        }
    }
}
