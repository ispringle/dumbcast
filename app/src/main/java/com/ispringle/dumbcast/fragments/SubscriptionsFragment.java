package com.ispringle.dumbcast.fragments;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.ispringle.dumbcast.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Fragment displaying the user's podcast subscriptions.
 * Currently shows stub/test data for UI verification.
 */
public class SubscriptionsFragment extends Fragment {

    private ListView listView;
    private ArrayAdapter<String> adapter;

    public SubscriptionsFragment() {
        // Required empty public constructor
    }

    public static SubscriptionsFragment newInstance() {
        return new SubscriptionsFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_subscriptions, container, false);

        listView = view.findViewById(R.id.subscriptions_list);

        // Populate with test data
        List<String> testPodcasts = createTestData();
        adapter = new ArrayAdapter<>(
            getContext(),
            android.R.layout.simple_list_item_1,
            testPodcasts
        );
        listView.setAdapter(adapter);

        return view;
    }

    /**
     * Creates test data for UI verification.
     * In production, this will be replaced with actual podcast data from the database.
     */
    private List<String> createTestData() {
        List<String> testData = new ArrayList<>();
        testData.add("The Test Podcast");
        testData.add("Another Test Show");
        testData.add("Example Podcast #3");
        return testData;
    }
}
