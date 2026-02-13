package com.ispringle.dumbcast.fragments;

import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.ispringle.dumbcast.R;
import com.ispringle.dumbcast.adapters.PodcastAdapter;
import com.ispringle.dumbcast.data.DatabaseHelper;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.data.Podcast;
import com.ispringle.dumbcast.data.PodcastRepository;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Fragment displaying the user's podcast subscriptions.
 * Loads podcasts from the database and displays them in a list.
 * Click on a podcast to navigate to its episode list.
 */
public class SubscriptionsFragment extends Fragment {

    private static final String TAG = "SubscriptionsFragment";

    private ListView listView;
    private TextView emptyText;
    private PodcastAdapter adapter;
    private PodcastRepository podcastRepository;
    private EpisodeRepository episodeRepository;

    public SubscriptionsFragment() {
        // Required empty public constructor
    }

    public static SubscriptionsFragment newInstance() {
        return new SubscriptionsFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Initialize repositories
        DatabaseHelper dbHelper = new DatabaseHelper(getContext());
        podcastRepository = new PodcastRepository(dbHelper);
        episodeRepository = new EpisodeRepository(dbHelper);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_subscriptions, container, false);

        listView = view.findViewById(R.id.subscriptions_list);

        // Add empty state text (reusing the title TextView for now)
        emptyText = view.findViewById(R.id.subscriptions_title);

        // Initialize adapter with empty list
        adapter = new PodcastAdapter(getContext(), new ArrayList<Podcast>(), episodeRepository);
        listView.setAdapter(adapter);

        // Set up click listener to navigate to episode list
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Podcast podcast = adapter.getItem(position);
                if (podcast != null) {
                    navigateToEpisodeList(podcast);
                }
            }
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh data when fragment becomes visible
        loadPodcasts();
    }

    /**
     * Load podcasts from database on a background thread.
     */
    private void loadPodcasts() {
        new LoadPodcastsTask(this, podcastRepository).execute();
    }

    /**
     * Update the UI with loaded podcasts (called on main thread).
     * @param podcasts List of podcasts to display
     */
    private void updatePodcastList(List<Podcast> podcasts) {
        adapter.clear();
        adapter.addAll(podcasts);
        adapter.notifyDataSetChanged();

        // Show message if no podcasts
        if (podcasts.isEmpty()) {
            emptyText.setText("No subscriptions yet");
        } else {
            emptyText.setText(R.string.subscriptions_title);
        }

        Log.d(TAG, "Loaded " + podcasts.size() + " podcasts");
    }

    /**
     * Navigate to the episode list for a specific podcast.
     * @param podcast The podcast to view episodes for
     */
    private void navigateToEpisodeList(Podcast podcast) {
        EpisodeListFragment fragment = EpisodeListFragment.newInstanceForPodcast(podcast.getId());

        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, fragment);
        transaction.addToBackStack(null);
        transaction.commit();

        Log.d(TAG, "Navigating to episode list for podcast: " + podcast.getTitle());
    }

    /**
     * AsyncTask to load podcasts from database on a background thread.
     */
    private static class LoadPodcastsTask extends AsyncTask<Void, Void, List<Podcast>> {
        private final WeakReference<SubscriptionsFragment> fragmentRef;
        private final PodcastRepository repository;

        LoadPodcastsTask(SubscriptionsFragment fragment, PodcastRepository repository) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.repository = repository;
        }

        @Override
        protected List<Podcast> doInBackground(Void... voids) {
            return repository.getAllPodcasts();
        }

        @Override
        protected void onPostExecute(List<Podcast> podcasts) {
            SubscriptionsFragment fragment = fragmentRef.get();
            if (fragment != null && podcasts != null) {
                fragment.updatePodcastList(podcasts);
            }
        }
    }
}
