package com.ispringle.dumbcast.utils;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RssParser {
    private static final String ns = null;

    // Date format patterns
    private static final SimpleDateFormat RFC822_FORMAT =
        new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
    private static final SimpleDateFormat ISO8601_FORMAT =
        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);

    // Duration pattern for iTunes duration (HH:MM:SS, MM:SS, or seconds)
    private static final Pattern DURATION_PATTERN =
        Pattern.compile("(?:(\\d+):)?(\\d+):(\\d+)|^(\\d+)$");

    public RssFeed parse(InputStream in) throws XmlPullParserException, IOException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(in, null);
            parser.nextTag();
            return readRss(parser);
        } finally {
            in.close();
        }
    }

    private RssFeed readRss(XmlPullParser parser) throws XmlPullParserException, IOException {
        RssFeed feed = new RssFeed();
        parser.require(XmlPullParser.START_TAG, ns, "rss");

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            String name = parser.getName();
            if (name.equals("channel")) {
                readChannel(parser, feed);
            } else {
                skip(parser);
            }
        }
        return feed;
    }

    private void readChannel(XmlPullParser parser, RssFeed feed)
            throws XmlPullParserException, IOException {
        parser.require(XmlPullParser.START_TAG, ns, "channel");

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            String name = parser.getName();

            switch (name) {
                case "title":
                    feed.setTitle(readText(parser, "title"));
                    break;
                case "description":
                    feed.setDescription(readText(parser, "description"));
                    break;
                case "link":
                    feed.setLink(readText(parser, "link"));
                    break;
                case "image":
                    readImage(parser, feed);
                    break;
                case "item":
                    feed.addItem(readItem(parser));
                    break;
                default:
                    skip(parser);
                    break;
            }
        }
    }

    private RssFeed.RssItem readItem(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        parser.require(XmlPullParser.START_TAG, ns, "item");
        RssFeed.RssItem item = new RssFeed.RssItem();

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            String name = parser.getName();

            switch (name) {
                case "guid":
                    item.setGuid(readText(parser, "guid"));
                    break;
                case "title":
                    item.setTitle(readText(parser, "title"));
                    break;
                case "description":
                    item.setDescription(readText(parser, "description"));
                    break;
                case "link":
                    item.setLink(readText(parser, "link"));
                    break;
                case "pubDate":
                    String dateStr = readText(parser, "pubDate");
                    long timestamp = parseDate(dateStr);
                    item.setPublishedAt(timestamp);
                    break;
                case "enclosure":
                    String url = parser.getAttributeValue(null, "url");
                    String type = parser.getAttributeValue(null, "type");
                    String lengthStr = parser.getAttributeValue(null, "length");

                    if (url != null) {
                        item.setEnclosureUrl(url);
                    }
                    if (type != null) {
                        item.setEnclosureType(type);
                    }
                    if (lengthStr != null) {
                        try {
                            item.setEnclosureLength(Long.parseLong(lengthStr));
                        } catch (NumberFormatException e) {
                            // Ignore invalid length
                        }
                    }
                    parser.nextTag();
                    break;
                default:
                    // Handle iTunes and Podcast namespace tags
                    if (name.equals("duration") || name.endsWith(":duration")) {
                        String durationStr = readText(parser, name);
                        int duration = parseDuration(durationStr);
                        item.setDuration(duration);
                    } else if (name.equals("image") || name.endsWith(":image")) {
                        String imageUrl = parser.getAttributeValue(null, "href");
                        if (imageUrl == null) {
                            imageUrl = readText(parser, name);
                        }
                        if (imageUrl != null && !imageUrl.isEmpty()) {
                            item.setImageUrl(imageUrl);
                        }
                    } else if (name.equals("chapters") || name.endsWith(":chapters")) {
                        String chaptersUrl = parser.getAttributeValue(null, "url");
                        if (chaptersUrl != null && !chaptersUrl.isEmpty()) {
                            item.setChaptersUrl(chaptersUrl);
                        }
                        skip(parser);
                    } else {
                        skip(parser);
                    }
                    break;
            }
        }
        return item;
    }

    private void readImage(XmlPullParser parser, RssFeed feed)
            throws XmlPullParserException, IOException {
        parser.require(XmlPullParser.START_TAG, ns, "image");

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            String name = parser.getName();

            if (name.equals("url")) {
                feed.setImageUrl(readText(parser, "url"));
            } else {
                skip(parser);
            }
        }
    }

    private String readText(XmlPullParser parser, String tagName)
            throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, ns, tagName);
        String result = "";
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.getText();
            parser.nextTag();
        }
        parser.require(XmlPullParser.END_TAG, ns, tagName);
        return result;
    }

    private void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
        if (parser.getEventType() != XmlPullParser.START_TAG) {
            throw new IllegalStateException();
        }
        int depth = 1;
        while (depth != 0) {
            switch (parser.next()) {
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
            }
        }
    }

    private long parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return System.currentTimeMillis();
        }

        // Try RFC822 format (standard RSS)
        try {
            Date date = RFC822_FORMAT.parse(dateStr);
            if (date != null) {
                return date.getTime();
            }
        } catch (ParseException e) {
            // Try next format
        }

        // Try ISO8601 format
        try {
            Date date = ISO8601_FORMAT.parse(dateStr);
            if (date != null) {
                return date.getTime();
            }
        } catch (ParseException e) {
            // Use current time as fallback
        }

        return System.currentTimeMillis();
    }

    private int parseDuration(String durationStr) {
        if (durationStr == null || durationStr.isEmpty()) {
            return 0;
        }

        durationStr = durationStr.trim();
        Matcher matcher = DURATION_PATTERN.matcher(durationStr);

        if (matcher.matches()) {
            // Check if it's just seconds (group 4)
            if (matcher.group(4) != null) {
                try {
                    return Integer.parseInt(matcher.group(4));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }

            // Parse HH:MM:SS or MM:SS format
            try {
                String hours = matcher.group(1);
                String minutes = matcher.group(2);
                String seconds = matcher.group(3);

                int totalSeconds = 0;
                if (hours != null) {
                    totalSeconds += Integer.parseInt(hours) * 3600;
                }
                if (minutes != null) {
                    totalSeconds += Integer.parseInt(minutes) * 60;
                }
                if (seconds != null) {
                    totalSeconds += Integer.parseInt(seconds);
                }
                return totalSeconds;
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        return 0;
    }
}
