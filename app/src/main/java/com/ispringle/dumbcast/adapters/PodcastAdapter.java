package com.ispringle.dumbcast.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.ispringle.dumbcast.R;
import com.ispringle.dumbcast.data.EpisodeRepository;
import com.ispringle.dumbcast.data.Podcast;

import java.util.List;

/**
 * Custom ArrayAdapter for displaying Podcast objects in a ListView.
 * Uses ViewHolder pattern for performance.
 * Shows podcast title, description (truncated), and episode count.
 */
public class PodcastAdapter extends ArrayAdapter<Podcast> {

    private final LayoutInflater inflater;
    private final EpisodeRepository episodeRepository;

    /**
     * ViewHolder pattern to cache view references for performance.
     */
    private static class ViewHolder {
        TextView titleText;
        TextView descriptionText;
        TextView episodeCountText;
    }

    public PodcastAdapter(Context context, List<Podcast> podcasts, EpisodeRepository episodeRepository) {
        super(context, 0, podcasts);
        this.inflater = LayoutInflater.from(context);
        this.episodeRepository = episodeRepository;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_podcast, parent, false);
            holder = new ViewHolder();
            holder.titleText = convertView.findViewById(R.id.podcast_title);
            holder.descriptionText = convertView.findViewById(R.id.podcast_description);
            holder.episodeCountText = convertView.findViewById(R.id.podcast_episode_count);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Podcast podcast = getItem(position);
        if (podcast != null) {
            // Set title
            holder.titleText.setText(podcast.getTitle());

            // Set description (truncated to 100 characters for list view)
            String description = podcast.getDescription();
            if (description != null && !description.isEmpty()) {
                if (description.length() > 100) {
                    description = description.substring(0, 97) + "...";
                }
                holder.descriptionText.setText(description);
                holder.descriptionText.setVisibility(View.VISIBLE);
            } else {
                holder.descriptionText.setVisibility(View.GONE);
            }

            // Get and display episode count
            int episodeCount = episodeRepository.getEpisodeCountByPodcast(podcast.getId());
            holder.episodeCountText.setText(episodeCount + " eps");
        }

        return convertView;
    }
}
