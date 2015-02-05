package com.afollestad.cabinet.fragments;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SearchView;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.cabinet.R;
import com.afollestad.cabinet.adapters.FileAdapter;
import com.afollestad.cabinet.cab.CopyCab;
import com.afollestad.cabinet.cab.CutCab;
import com.afollestad.cabinet.cab.MainCab;
import com.afollestad.cabinet.cab.base.BaseCab;
import com.afollestad.cabinet.cab.base.BaseFileCab;
import com.afollestad.cabinet.comparators.AlphabeticalComparator;
import com.afollestad.cabinet.comparators.ExtensionComparator;
import com.afollestad.cabinet.comparators.HighLowSizeComparator;
import com.afollestad.cabinet.comparators.LastModifiedComparator;
import com.afollestad.cabinet.comparators.LowHighSizeComparator;
import com.afollestad.cabinet.file.CloudFile;
import com.afollestad.cabinet.file.LocalFile;
import com.afollestad.cabinet.file.base.File;
import com.afollestad.cabinet.file.base.FileFilter;
import com.afollestad.cabinet.services.NetworkService;
import com.afollestad.cabinet.sftp.SftpClient;
import com.afollestad.cabinet.ui.MainActivity;
import com.afollestad.cabinet.utils.PauseOnScrollListener;
import com.afollestad.cabinet.utils.Pins;
import com.afollestad.cabinet.utils.Utils;
import com.afollestad.cabinet.zip.Unzipper;
import com.afollestad.cabinet.zip.Zipper;
import com.afollestad.materialdialogs.MaterialDialog;
import com.nostra13.universalimageloader.core.ImageLoader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class DirectoryFragment extends Fragment implements FileAdapter.IconClickListener, FileAdapter.ItemClickListener, FileAdapter.MenuClickListener, MainActivity.FabListener {

    private final transient BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null && intent.getAction().equals(NetworkService.DISCONNECT_SFTP)) {
                ((MainActivity) getActivity()).switchDirectory(null, true);
            }
        }
    };

    public DirectoryFragment() {
    }

    public FileAdapter mAdapter;
    private GridLayoutManager mLayoutManager;
    private RecyclerView mRecyclerView;

    private File mDirectory;
    public String mQuery;
    public boolean showHidden;
    public int sorter;
    public String filter;
    private Thread searchThread;

    public File getDirectory() {
        return mDirectory;
    }

    public static DirectoryFragment create(File directory) {
        DirectoryFragment frag = new DirectoryFragment();
        Bundle b = new Bundle();
        b.putSerializable("path", directory);
        frag.setArguments(b);
        return frag;
    }

    public static DirectoryFragment create(File directory, String query) {
        DirectoryFragment frag = new DirectoryFragment();
        Bundle b = new Bundle();
        b.putSerializable("path", directory);
        b.putString("query", query);
        frag.setArguments(b);
        return frag;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        mDirectory = (File) getArguments().getSerializable("path");
        mQuery = getArguments().getString("query");
        super.onCreate(savedInstanceState);

        if (mQuery != null) mQuery = mQuery.trim();
        Activity act = getActivity();
        showHidden = Utils.getShowHidden(act);
        sorter = Utils.getSorter(act);
        filter = Utils.getFilter(act);
    }

    @Override
    public void onResume() {
        super.onResume();

        MainActivity act = (MainActivity) getActivity();
        act.toggleFab(false);
        act.registerReceiver(mReceiver, new IntentFilter(NetworkService.DISCONNECT_SFTP));
        if (mQuery != null) {
            act.setTitle(Html.fromHtml(getString(R.string.search_x, mQuery)));
        } else {
            act.setTitle(mDirectory.getDisplay());
        }

        BaseCab cab = ((MainActivity) getActivity()).getCab();
        if (cab != null && cab instanceof BaseFileCab) {
            mAdapter.restoreCheckedPaths(((BaseFileCab) cab).getFiles());
            if (act.shouldAttachFab) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                MainActivity act = (MainActivity) getActivity();
                                BaseFileCab cab = (BaseFileCab) act.getCab()
                                        .setFragment(DirectoryFragment.this);
                                cab.start();
                                act.shouldAttachFab = false;
                            }
                        });
                    }
                }).start();
            } else cab.setFragment(this);
        }

        ((NavigationDrawerFragment) act.getFragmentManager().findFragmentByTag("NAV_DRAWER")).selectFile(mDirectory);
        showHidden = Utils.getShowHidden(getActivity());
        sorter = Utils.getSorter(getActivity());
        filter = Utils.getFilter(getActivity());

        reload();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (searchThread != null) searchThread.interrupt();
        try {
            getActivity().unregisterReceiver(mReceiver);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.fragment_menu, menu);
        switch (sorter) {
            default:
                menu.findItem(R.id.sortName).setChecked(true);
                break;
            case 2:
                menu.findItem(R.id.sortExtension).setChecked(true);
                break;
            case 3:
                menu.findItem(R.id.sortSizeLowHigh).setChecked(true);
                break;
            case 4:
                menu.findItem(R.id.sortSizeHighLow).setChecked(true);
                break;
            case 5:
                menu.findItem(R.id.sortLastModified).setChecked(true);
                break;
        }
        if (getActivity() != null) {
            boolean foldersFirst = PreferenceManager.getDefaultSharedPreferences(getActivity())
                    .getBoolean("folders_first", true);
            menu.findItem(R.id.foldersFirstCheck).setChecked(foldersFirst);
        }

        if (filter != null) {
            if (filter.equals("archives")) {
                menu.findItem(R.id.filterArchives).setChecked(true);
            } else {
                String[] splitFilter = filter.split(":");
                if (splitFilter[0].equals("mime")) {
                    switch (splitFilter[1]) {
                        case "text":
                            menu.findItem(R.id.filterText).setChecked(true);
                            break;
                        case "image":
                            menu.findItem(R.id.filterImage).setChecked(true);
                            break;
                        case "audio":
                            menu.findItem(R.id.filterAudio).setChecked(true);
                            break;
                        case "video":
                            menu.findItem(R.id.filterVideo).setChecked(true);
                            break;
                    }
                } else if (splitFilter[0].equals("ext")) {
                    menu.findItem(R.id.filterOther).setChecked(true);
                }
            }
        } else menu.findItem(R.id.filterNone).setChecked(true);

        if (getActivity() != null) {
            switch (Utils.getGridSize(getActivity())) {
                default:
                    menu.findItem(R.id.gridSizeOne).setChecked(true);
                    break;
                case 2:
                    menu.findItem(R.id.gridSizeTwo).setChecked(true);
                    break;
                case 3:
                    menu.findItem(R.id.gridSizeThree).setChecked(true);
                    break;
                case 4:
                    menu.findItem(R.id.gridSizeFour).setChecked(true);
                    break;
                case 5:
                    menu.findItem(R.id.gridSizeFive).setChecked(true);
                    break;
                case 6:
                    menu.findItem(R.id.gridSizeSix).setChecked(true);
                    break;
            }
            menu.findItem(R.id.showHidden).setChecked(Utils.getShowHidden(getActivity()));
        }

        boolean canShow = true;
        if (!mDirectory.isRemote()) {
            try {
                canShow = mDirectory.existsSync();
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(getActivity(), e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
            }
        }
        boolean searchMode = mQuery != null;
        menu.findItem(R.id.sort).setVisible(canShow);
        menu.findItem(R.id.goUp).setVisible(!searchMode && canShow && mDirectory.getParent() != null);

        final MenuItem search = menu.findItem(R.id.search);
        if (canShow && !searchMode) {
            assert search != null;
            SearchView searchView = (SearchView) MenuItemCompat.getActionView(search);
            try {
                searchView.setQueryHint(getString(R.string.search_files));
            } catch (IllegalStateException e) {
                searchView.setQueryHint("Search files…");
            }
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    search.collapseActionView();
                    if (getActivity() != null)
                        ((MainActivity) getActivity()).search(mDirectory, query);
                    return false;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    return false;
                }
            });
        } else search.setVisible(false);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_recyclerview, null);
    }

    private void showNewFolderDialog(final Activity context) {
        Utils.showInputDialog(context, R.string.new_folder, R.string.untitled, null,
                new Utils.InputCallback() {
                    @Override
                    public void onInput(String newName) {
                        if (newName.isEmpty())
                            newName = getString(R.string.untitled);
                        final File dir = mDirectory.isRemote() ?
                                new CloudFile(context, (CloudFile) mDirectory, newName, true) :
                                new LocalFile(context, mDirectory, newName);
                        dir.exists(new File.BooleanCallback() {
                            @Override
                            public void onComplete(boolean result) {
                                if (!result) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            dir.mkdir(new SftpClient.CompletionCallback() {
                                                @Override
                                                public void onComplete() {
                                                    runOnUiThread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            reload();
                                                        }
                                                    });
                                                }

                                                @Override
                                                public void onError(Exception e) {
                                                    Utils.showErrorDialog(context, e.getMessage());
                                                }
                                            });
                                        }
                                    });
                                } else {
                                    Utils.showErrorDialog(context, getString(R.string.directory_already_exists));
                                }
                            }

                            @Override
                            public void onError(Exception e) {
                                Utils.showErrorDialog(context, e.getMessage());
                            }
                        });
                    }
                }
        );
    }

    private void createNewFileDuplicate(final Activity context, final File file) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File newFile = Utils.checkDuplicatesSync(context, file);
                    newFile.createFile(new SftpClient.CompletionCallback() {
                        @Override
                        public void onComplete() {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    reload();
                                }
                            });
                        }

                        @Override
                        public void onError(final Exception e) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Utils.showErrorDialog(context, e.getMessage());
                                }
                            });
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Utils.showErrorDialog(context, e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void showNewFileDialog(final Activity context) {
        Utils.showInputDialog(context, R.string.new_file, R.string.untitled, null,
                new Utils.InputCallback() {
                    @Override
                    public void onInput(String newName) {
                        if (newName.isEmpty())
                            newName = getString(R.string.untitled);
                        final File newFile = mDirectory.isRemote() ?
                                new CloudFile(context, (CloudFile) mDirectory, newName, false) :
                                new LocalFile(context, mDirectory, newName);
                        newFile.exists(new File.BooleanCallback() {
                            @Override
                            public void onComplete(boolean result) {
                                if (!result) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            newFile.createFile(new SftpClient.CompletionCallback() {
                                                @Override
                                                public void onComplete() {
                                                    runOnUiThread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            reload();
                                                        }
                                                    });
                                                }

                                                @Override
                                                public void onError(final Exception e) {
                                                    runOnUiThread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            if (e != null)
                                                                Utils.showErrorDialog(context, e.getMessage());
                                                        }
                                                    });
                                                }
                                            });
                                        }
                                    });
                                } else {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            new MaterialDialog.Builder(getActivity())
                                                    .title(R.string.file_already_exists)
                                                    .content(R.string.file_already_exists_warning)
                                                    .positiveText(android.R.string.ok)
                                                    .negativeText(android.R.string.cancel)
                                                    .callback(new MaterialDialog.ButtonCallback() {
                                                        @Override
                                                        public void onPositive(MaterialDialog dialog) {
                                                            createNewFileDuplicate(context, newFile);
                                                        }
                                                    })
                                                    .build().show();
                                        }
                                    });
                                }
                            }

                            @Override
                            public void onError(Exception e) {
                                if (e != null)
                                    Utils.showErrorDialog(context, e.getMessage());
                            }
                        });
                    }
                }
        );
    }

    @Override
    public void onFabPressed(int action) {
        if (getActivity() != null) {
            switch (action) {
                case 0: // File
                    showNewFileDialog(getActivity());
                    break;
                case 1: // Folder
                    showNewFolderDialog(getActivity());
                    break;
                case 2: // Remote connection
                    new RemoteConnectionDialog(getActivity()).show();
                    break;
            }
        }
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mRecyclerView = (RecyclerView) view.findViewById(android.R.id.list);
        mRecyclerView.setOnScrollListener(new PauseOnScrollListener(ImageLoader.getInstance(), true, true, new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView view, int dx, int dy) {
                if (dy < 0) {
                    if (dy < -5) {
                        ((MainActivity) getActivity()).toggleFab(false);
                    }
                } else if (dy > 0) {
                    if (dy > 10) {
                        ((MainActivity) getActivity()).toggleFab(true);
                    }
                }
            }
        }));

        mLayoutManager = new GridLayoutManager(getActivity(), Utils.getGridSize(getActivity()));
        mRecyclerView.setLayoutManager(mLayoutManager);
        mAdapter = new FileAdapter(getActivity(), this, this, this, mQuery != null);
        mRecyclerView.setAdapter(mAdapter);

        ((MainActivity) getActivity()).setFabListener(this);
//        reload();
    }

    protected void runOnUiThread(Runnable runnable) {
        Activity act = getActivity();
        if (act != null && getView() != null)
            act.runOnUiThread(runnable);
    }

    public final void setListShown(boolean shown) {
        View v = getView();
        if (v != null) {
            if (shown) {
                v.findViewById(R.id.listFrame).setVisibility(View.VISIBLE);
                v.findViewById(android.R.id.progress).setVisibility(View.GONE);
                boolean showEmpty = mAdapter.getItemCount() == 0;
                v.findViewById(android.R.id.empty).setVisibility(showEmpty ? View.VISIBLE : View.GONE);
                ((RecyclerView) v.findViewById(android.R.id.list)).setAdapter(mAdapter);
            } else {
                v.findViewById(R.id.listFrame).setVisibility(View.GONE);
                v.findViewById(android.R.id.progress).setVisibility(View.VISIBLE);
            }
        }
    }

    protected final void setEmptyText(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                View v = getView();
                if (v != null) {
                    ((TextView) v.findViewById(R.id.emptyText)).setText(text);
                }
            }
        });
    }

    private Comparator<File> getComparator() {
        Comparator<File> comparator;
        switch (sorter) {
            default:
                comparator = new AlphabeticalComparator(getActivity());
                break;
            case 2:
                comparator = new ExtensionComparator(getActivity());
                break;
            case 3:
                comparator = new LowHighSizeComparator(getActivity());
                break;
            case 4:
                comparator = new HighLowSizeComparator(getActivity());
                break;
            case 5:
                comparator = new LastModifiedComparator(getActivity());
                break;
        }
        return comparator;
    }

    public void search() {
        setListShown(false);
        searchThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<File> results = searchDir(showHidden, mDirectory);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (searchThread.isInterrupted()) return;
                            if (results != null && results.size() > 0)
                                Collections.sort(results, getComparator());
                            mAdapter.set(results);
                            setListShown(true);
                        }
                    });
                } catch (final Exception e) {
                    e.printStackTrace();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (mDirectory.isRemote()) {
                                ((MainActivity) getActivity()).disableFab(false, false);
                            }
                            try {
                                String message = e.getMessage();
                                if (message.trim().isEmpty())
                                    message = getString(R.string.error);
                                setEmptyText(message);
                                setListShown(true);
                            } catch (IllegalStateException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }
            }
        });
        searchThread.start();
    }

    private List<File> searchDir(boolean includeHidden, File dir) throws Exception {
        return dir.searchRecursive(includeHidden, new FileFilter() {
            @Override
            public boolean accept(File file) {
                if (mQuery.startsWith("type:")) {
                    String target = mQuery.substring(mQuery.indexOf(':') + 1);
                    setEmptyText(getString(R.string.no_x_files, target));
                    return file.getExtension().equalsIgnoreCase(target);
                }
                return file.getName().toLowerCase().contains(mQuery.toLowerCase());
            }
        });
    }

    private String getFilterDisplay() {
        if (filter == null) return "?";
        else if (filter.equals("archives")) {
            return getString(R.string.archives);
        } else {
            String[] splitFilter = filter.split(":");
            if (splitFilter.length == 2) {
                if (splitFilter[0].equals("mime")) {
                    switch (splitFilter[1]) {
                        case "text":
                            return getString(R.string.text);
                        case "image":
                            return getString(R.string.image);
                        case "audio":
                            return getString(R.string.audio);
                        case "video":
                            return getString(R.string.video);
                    }
                } else if (splitFilter[0].equals("ext")) {
                    return splitFilter[1];
                }
            }
            return splitFilter[0];
        }
    }

    public FileAdapter getAdapter() {
        return mAdapter;
    }

    public void changeLayout() {
        View v = getView();
        if (v == null) return;
        RecyclerView mRecyclerView = (RecyclerView) v.findViewById(android.R.id.list);
        mLayoutManager = new GridLayoutManager(getActivity(), Utils.getGridSize(getActivity()));
        mRecyclerView.setLayoutManager(mLayoutManager);
        mAdapter = new FileAdapter(getActivity(), this, this, this, mQuery != null);
        mRecyclerView.setAdapter(mAdapter);
        getActivity().invalidateOptionsMenu(); // update checkbox
        reload();
    }

    private int persistedPosition;
    private int persistedOffset;

    public void reload() {
        final View v = getView();
        if (getActivity() == null || v == null) {
            return;
        } else if (mQuery != null) {
            search();
            return;
        }

        MainActivity act = (MainActivity) getActivity();

        setListShown(false);
        mAdapter.showLastModified = (sorter == 5);
        mDirectory.setContext(getActivity());

        FileFilter lsFilter = null;
        if (filter != null) {
            String display = getFilterDisplay();
            act.setStatus(R.string.filter_active, display);
            setEmptyText(getString(R.string.no_files_filter, display));
            lsFilter = new FileFilter() {
                @Override
                public boolean accept(File file) {
                    if (file.isDirectory()) return true;
                    if (filter.equals("archives")) {
                        String ext = file.getExtension();
                        return ext.equals("zip") || ext.equals("rar") || ext.equals("tar") ||
                                ext.equals("tar.gz") || ext.equals(".7z");
                    } else {
                        String[] splitFilter = filter.split(":");
                        if (splitFilter[0].equals("mime")) {
                            return file.getMimeType().startsWith(splitFilter[1]);
                        } else {
                            return file.getExtension().equals(splitFilter[1]);
                        }
                    }
                }
            };
        } else {
            act.setStatus(0, null);
            setEmptyText(getString(R.string.no_files));
        }

        ((ImageView) v.findViewById(R.id.emptyImage)).setImageResource(
                Utils.resolveDrawable(getActivity(), R.attr.empty_image));

        mDirectory.setContext(getActivity());
        persistedPosition = -1;
        persistedOffset = 0;
        if (mAdapter.getItemCount() > 0 && !getDirectory().isRemote()) {
            persistedPosition = mLayoutManager.findFirstVisibleItemPosition();
            persistedOffset = (int) mRecyclerView.getChildAt(0).getY();
        }
        mDirectory.listFiles(showHidden, lsFilter, new File.ArrayCallback() {
            @Override
            public void onComplete(final File[] results) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mAdapter.clear();
                        if (results != null && results.length > 0) {
                            Arrays.sort(results, getComparator());
                            for (File fi : results)
                                mAdapter.add(fi);
                            if (persistedPosition > -1) {
                                mLayoutManager.scrollToPositionWithOffset(persistedPosition, persistedOffset);
                            }
                        }
                        try {
                            setListShown(true);
                        } catch (IllegalStateException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }

            @Override
            public void onError(final Exception e) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ((ImageView) v.findViewById(R.id.emptyImage)).setImageResource(
                                Utils.resolveDrawable(getActivity(), R.attr.empty_image_error));
                        if (mDirectory.isRemote()) {
                            ((MainActivity) getActivity()).disableFab(false, false);
                        }
                        try {
                            String message = e.getMessage();
                            if (message.trim().isEmpty())
                                message = getString(R.string.error);
                            setEmptyText(message);
                            setListShown(true);
                        } catch (IllegalStateException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }
        });
    }

    public void resort() {
        if (mAdapter.getFiles() != null && mAdapter.getFiles().size() > 0)
            Collections.sort(mAdapter.getFiles(), getComparator());
        mAdapter.notifyDataSetChanged();
    }

    @SuppressLint("CommitPrefEdits")
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.goUp:
                ((MainActivity) getActivity()).switchDirectory(mDirectory.getParent(), false);
                break;
            case R.id.showHidden:
                Utils.setShowHidden(this, !showHidden);
                break;
            case R.id.sortName:
                item.setChecked(true);
                Utils.setSorter(this, 1);
                break;
            case R.id.sortExtension:
                item.setChecked(true);
                Utils.setSorter(this, 2);
                break;
            case R.id.sortSizeLowHigh:
                item.setChecked(true);
                Utils.setSorter(this, 3);
                break;
            case R.id.sortSizeHighLow:
                item.setChecked(true);
                Utils.setSorter(this, 4);
                break;
            case R.id.sortLastModified:
                item.setChecked(true);
                Utils.setSorter(this, 5);
                break;
            case R.id.foldersFirstCheck:
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
                boolean foldersFirst = prefs.getBoolean("folders_first", true);
                prefs.edit().putBoolean("folders_first", !foldersFirst).commit();
                item.setChecked(!foldersFirst);
                resort();
                break;
            case R.id.filterNone:
                item.setChecked(true);
                Utils.setFilter(this, null);
                break;
            case R.id.filterText:
                item.setChecked(true);
                Utils.setFilter(this, "mime:text");
                break;
            case R.id.filterImage:
                item.setChecked(true);
                Utils.setFilter(this, "mime:image");
                break;
            case R.id.filterAudio:
                item.setChecked(true);
                Utils.setFilter(this, "mime:audio");
                break;
            case R.id.filterVideo:
                item.setChecked(true);
                Utils.setFilter(this, "mime:video");
                break;
            case R.id.filterArchives:
                item.setChecked(true);
                Utils.setFilter(this, "archives");
                break;
            case R.id.filterOther: {
                final MenuItem fItem = item;
                String prefill = null;
                if (filter != null && filter.startsWith("ext"))
                    prefill = filter.split(":")[1];
                Utils.showInputDialog(getActivity(), R.string.extension, R.string.extension_hint, prefill, new Utils.InputCancelCallback() {
                    @Override
                    public void onInput(String input) {
                        fItem.setChecked(true);
                        if (input.startsWith(".")) input = input.substring(1);
                        Utils.setFilter(DirectoryFragment.this, "ext:" + input);
                    }

                    @Override
                    public void onCancel() {
                        fItem.setChecked(false);
                    }
                });
                break;
            }
            case R.id.gridSizeOne:
                item.setChecked(true);
                Utils.setGridSize(this, 1);
                break;
            case R.id.gridSizeTwo:
                item.setChecked(true);
                Utils.setGridSize(this, 2);
                break;
            case R.id.gridSizeThree:
                item.setChecked(true);
                Utils.setGridSize(this, 3);
                break;
            case R.id.gridSizeFour:
                item.setChecked(true);
                Utils.setGridSize(this, 4);
                break;
            case R.id.gridSizeFive:
                item.setChecked(true);
                Utils.setGridSize(this, 5);
                break;
            case R.id.gridSizeSix:
                item.setChecked(true);
                Utils.setGridSize(this, 6);
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onIconClicked(int index, File file, boolean added) {
        MainActivity activity = (MainActivity) getActivity();
        BaseCab cab = activity.getCab();
        if (cab != null && (cab instanceof CopyCab || cab instanceof CutCab) && cab.isActive()) {
            if (added) ((BaseFileCab) cab).addFile(file, false);
            else ((BaseFileCab) cab).removeFile(file, false);
        } else {
            boolean shouldCreateCab = cab == null || !cab.isActive() || !(cab instanceof MainCab) && added;
            if (shouldCreateCab)
                activity.setCab(new MainCab()
                        .setFragment(this).setFile(file, false).start());
            else {
                if (added) ((BaseFileCab) cab).addFile(file, false);
                else ((BaseFileCab) cab).removeFile(file, false);
            }
        }

        activity.invalidateToolbarColors(false);
    }

    @Override
    public void onItemClicked(int index, File file) {
        if (file.isDirectory()) {
            ((MainActivity) getActivity()).switchDirectory(file, false);
        } else {
            if (((MainActivity) getActivity()).pickMode) {
                if (file.isRemote()) {
                    Utils.downloadFile((MainActivity) getActivity(), file, new Utils.FileCallback() {
                        @Override
                        public void onFile(File file) {
                            Activity act = getActivity();
                            Intent intent = act.getIntent()
                                    .setData(Uri.fromFile(file.toJavaFile()));
                            act.setResult(Activity.RESULT_OK, intent);
                            act.finish();
                        }
                    });
                } else {
                    Activity act = getActivity();
                    Intent intent = act.getIntent()
                            .setData(Uri.fromFile(file.toJavaFile()));
                    act.setResult(Activity.RESULT_OK, intent);
                    act.finish();
                }
            } else {
                if (file.getExtension().equals("zip")) {
                    final File fFile = file;
                    new MaterialDialog.Builder(getActivity())
                            .title(R.string.unzip)
                            .content(R.string.auto_unzip_prompt)
                            .positiveText(android.R.string.ok)
                            .negativeText(android.R.string.cancel)
                            .callback(new MaterialDialog.ButtonCallback() {
                                @Override
                                public void onPositive(MaterialDialog dialog) {
                                    List<File> files = new ArrayList<>();
                                    files.add(fFile);
                                    Unzipper.unzip(DirectoryFragment.this, files, null);
                                }

                                @Override
                                public void onNegative(MaterialDialog dialog) {
                                    Utils.openFile((MainActivity) getActivity(), fFile, false);
                                }
                            }).show();
                } else {
                    Utils.openFile((MainActivity) getActivity(), file, false);
                }
            }
        }
    }

    private void shareFile(File file) {
        try {
            final Uri uri = Uri.fromFile(file.toJavaFile());
            getActivity().startActivity(new Intent(Intent.ACTION_SEND).setDataAndType(uri, "*/*")
                    .putExtra(Intent.EXTRA_STREAM, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(getActivity(), R.string.no_apps_for_sharing, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onMenuItemClick(final File file, MenuItem item) {
        switch (item.getItemId()) {
            case R.id.pin:
                Pins.add(getActivity(), new Pins.Item(file));
                ((MainActivity) getActivity()).reloadNavDrawer(true);
                break;
            case R.id.openAs:
                Utils.openFile((MainActivity) getActivity(), file, true);
                break;
            case R.id.copy: {
                BaseCab cab = ((MainActivity) getActivity()).getCab();
                boolean shouldCreateCopy = cab == null || !cab.isActive() || !(cab instanceof CopyCab);
                if (shouldCreateCopy) {
                    if (cab != null && cab instanceof BaseFileCab) {
                        ((BaseFileCab) cab).overrideDestroy = true;
                    }
                    ((MainActivity) getActivity()).setCab(new CopyCab()
                            .setFragment(this).setFile(file, true).start());
                } else ((BaseFileCab) cab).setFragment(this).addFile(file, true);
                break;
            }
            case R.id.cut: {
                BaseCab cab = ((MainActivity) getActivity()).getCab();
                boolean shouldCreateCut = cab == null || !cab.isActive() || !(cab instanceof CutCab);
                if (shouldCreateCut) {
                    if (cab != null && cab instanceof BaseFileCab) {
                        ((BaseFileCab) cab).overrideDestroy = true;
                    }
                    ((MainActivity) getActivity()).setCab(new CutCab()
                            .setFragment(this).setFile(file, true).start());
                } else ((BaseFileCab) cab).setFragment(this).addFile(file, true);
                break;
            }
            case R.id.rename:
                Utils.showInputDialog(getActivity(), R.string.rename, 0, file.getName(), new Utils.InputCallback() {
                    @Override
                    public void onInput(String text) {
                        if (!text.contains("."))
                            text += file.getExtension();
                        final File newFile = file.isRemote() ?
                                new CloudFile(getActivity(), (CloudFile) file.getParent(), text, file.isDirectory()) :
                                new LocalFile(getActivity(), file.getParent(), text);
                        file.rename(newFile, new SftpClient.FileCallback() {
                            @Override
                            public void onComplete(File newFile) {
                                reload();
                                if (((MainActivity) getActivity()).getCab() != null &&
                                        ((MainActivity) getActivity()).getCab() instanceof BaseFileCab) {
                                    int cabIndex = ((BaseFileCab) ((MainActivity) getActivity()).getCab()).findFile(file);
                                    if (cabIndex > -1)
                                        ((BaseFileCab) ((MainActivity) getActivity()).getCab()).setFile(cabIndex, newFile, true);
                                    Toast.makeText(getActivity(), getString(R.string.renamed_to, newFile.getPath()), Toast.LENGTH_SHORT).show();
                                }
                            }

                            @Override
                            public void onError(Exception e) {
                                // Ignore
                            }
                        });
                    }
                });
                break;
            case R.id.zip:
                final List<File> files = new ArrayList<>();
                files.add(file);
                if (file.getExtension().equals("zip")) {
                    Unzipper.unzip(this, files, null);
                } else {
                    Zipper.zip(this, files, null);
                }
                break;
            case R.id.share:
                if (file.isRemote()) {
                    Utils.downloadFile((MainActivity) getActivity(), file, new Utils.FileCallback() {
                        @Override
                        public void onFile(File file) {
                            shareFile(file);
                        }
                    });
                } else {
                    shareFile(file);
                }
                break;
            case R.id.delete:
                Utils.showConfirmDialog(getActivity(), R.string.delete, R.string.confirm_delete, file.getName(), new Utils.ClickListener() {
                    @Override
                    public void onPositive(int which, View view) {
                        file.delete(new SftpClient.CompletionCallback() {
                            @Override
                            public void onComplete() {
                                if (Pins.remove(getActivity(), file))
                                    ((MainActivity) getActivity()).reloadNavDrawer();
                                mAdapter.remove(file);
                                MainActivity act = (MainActivity) getActivity();
                                if (act.getCab() != null && act.getCab() instanceof BaseFileCab) {
                                    BaseFileCab cab = (BaseFileCab) act.getCab();
                                    if (cab.getFiles() != null && cab.getFiles().size() > 0) {
                                        List<File> files = new ArrayList<>();
                                        files.addAll(cab.getFiles()); // copy so it doesn't get modified by CAB functions
                                        cab.removeFile(file, true);
                                        for (File fi : files) {
                                            if (fi.getPath().startsWith(file.getPath())) {
                                                cab.removeFile(fi, true);
                                            }
                                        }
                                    }
                                }
                            }

                            @Override
                            public void onError(Exception e) {
                                // Ignore
                            }
                        });
                    }
                });
                break;
            case R.id.details:
                DetailsDialog.create(file).show(getActivity().getFragmentManager(), "DETAILS_DIALOG");
                break;
        }
    }
}