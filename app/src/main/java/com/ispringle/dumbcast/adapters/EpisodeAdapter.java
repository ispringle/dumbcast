package com.ispringle.dumbcast.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.ispringle.dumbcast.R;
import com.ispringle.dumbcast.data.Episode;
import com.ispringle.dumbcast.data.EpisodeState;
import com.ispringle.dumbcast.data.Podcast;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom ArrayAdapter for displaying Episode objects in a ListView.
 * Uses ViewHolder pattern for performance.
 * Shows episode title, podcast name, duration, download status, and state badges.
 */
public class EpisodeAdapter extends ArrayAdapter<Episode> {

    private final LayoutInflater inflater;
    private final Map<Long, Podcast> podcastCache;

    /**
     * ViewHolder pattern to cache view references for performance.
     */
    private static class ViewHolder {
        TextView titleText;
        TextView podcastNameText;
        TextView durationText;
        TextView stateBadge;
        TextView downloadStatus;
    }

    public EpisodeAdapter(Context context, List<Episode> episodes, Map<Long, Podcast> podcastCache) {
        super(context, 0, episodes);
        this.inflater = LayoutInflater.from(context);
        this.podcastCache = podcastCache != null ? podcastCache : new HashMap<Long, Podcast>();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_episode, parent, false);
            holder = new ViewHolder();
            holder.titleText = convertView.findViewById(R.id.episode_title);
            holder.podcastNameText = convertView.findViewById(R.id.episode_podcast_name);
            holder.durationText = convertView.findViewById(R.id.episode_duration);
            holder.stateBadge = convertView.findViewById(R.id.episode_state_badge);
            holder.downloadStatus = convertView.findViewById(R.id.episode_download_status);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Episode episode = getItem(position);
        if (episode != null) {
            // Set title
            holder.titleText.setText(episode.getTitle());

            // Set podcast name from cached map
            Podcast podcast = podcastCache.get(episode.getPodcastId());
            if (podcast != null) {
                holder.podcastNameText.setText(podcast.getTitle());
            } else {
                holder.podcastNameText.setText("");
            }

            // Set duration
            int duration = episode.getDuration();
            if (duration > 0) {
                holder.durationText.setText(formatDuration(duration));
                holder.durationText.setVisibility(View.VISIBLE);
            } else {
                holder.durationText.setVisibility(View.GONE);
            }

            // Set state badge (only for NEW and BACKLOG)
            EpisodeState state = episode.getState();
            if (state == EpisodeState.NEW || state == EpisodeState.BACKLOG) {
                holder.stateBadge.setText(state.name());
                holder.stateBadge.setVisibility(View.VISIBLE);
            } else {
                holder.stateBadge.setVisibility(View.GONE);
            }

            // Set download status
            if (episode.isDownloaded()) {
                holder.downloadStatus.setText("Downloaded");
                holder.downloadStatus.setVisibility(View.VISIBLE);
            } else {
                holder.downloadStatus.setVisibility(View.GONE);
            }
        }

        return convertView;
    }

    /**
     * Format duration in seconds to human-readable format (e.g., "1h 23m" or "45m").
     * @param seconds Duration in seconds
     * @return Formatted duration string
     */
    private String formatDuration(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;

        if (hours > 0) {
            return hours + "h " + minutes + "m";
        } else {
            return minutes + "m";
        }
    }
}
