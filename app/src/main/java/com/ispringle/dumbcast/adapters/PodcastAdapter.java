package com.ispringle.dumbcast.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.ispringle.dumbcast.R;
import com.ispringle.dumbcast.data.Podcast;
import com.ispringle.dumbcast.utils.ImageLoader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom ArrayAdapter for displaying Podcast objects in a ListView.
 * Uses ViewHolder pattern for performance.
 * Shows podcast artwork, title, description (truncated), and episode count.
 */
public class PodcastAdapter extends ArrayAdapter<Podcast> {

    private final LayoutInflater inflater;
    private final Map<Long, Integer> episodeCounts;

    /**
     * ViewHolder pattern to cache view references for performance.
     */
    private static class ViewHolder {
        ImageView artworkImage;
        TextView titleText;
        TextView descriptionText;
        TextView episodeCountText;
    }

    public PodcastAdapter(Context context, List<Podcast> podcasts, Map<Long, Integer> episodeCounts) {
        super(context, 0, podcasts);
        this.inflater = LayoutInflater.from(context);
        this.episodeCounts = episodeCounts != null ? episodeCounts : new HashMap<Long, Integer>();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            holder = new ViewHolder();
            convertView = inflater.inflate(R.layout.item_podcast, parent, false);
            holder.artworkImage = convertView.findViewById(R.id.podcast_artwork);
            holder.titleText = convertView.findViewById(R.id.podcast_title);
            holder.descriptionText = convertView.findViewById(R.id.podcast_description);
            holder.episodeCountText = convertView.findViewById(R.id.podcast_episode_count);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Podcast podcast = getItem(position);
        if (podcast != null) {
            // Load podcast artwork
            ImageLoader.getInstance(getContext()).loadImage(
                getContext(),
                podcast.getArtworkUrl(),
                holder.artworkImage
            );

            // Set title
            holder.titleText.setText(podcast.getTitle());

            // Set description (parse HTML and truncate to 100 characters)
            String description = podcast.getDescription();
            if (description != null && !description.isEmpty()) {
                // Parse HTML for proper formatting
                CharSequence formattedDescription;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    formattedDescription = android.text.Html.fromHtml(description, android.text.Html.FROM_HTML_MODE_COMPACT);
                } else {
                    formattedDescription = android.text.Html.fromHtml(description);
                }

                // Truncate if needed
                if (formattedDescription.length() > 100) {
                    holder.descriptionText.setText(formattedDescription.subSequence(0, 97) + "...");
                } else {
                    holder.descriptionText.setText(formattedDescription);
                }
                holder.descriptionText.setVisibility(View.VISIBLE);
            } else {
                holder.descriptionText.setVisibility(View.GONE);
            }

            // Get and display episode count from cached map
            Integer episodeCount = episodeCounts.get(podcast.getId());
            if (episodeCount != null) {
                holder.episodeCountText.setText(episodeCount + " eps");
            } else {
                holder.episodeCountText.setText("0 eps");
            }
        }

        return convertView;
    }
}
