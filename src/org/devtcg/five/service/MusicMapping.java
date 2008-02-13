package org.devtcg.five.service;

import java.util.HashMap;
import java.util.Scanner;

import org.devtcg.five.provider.Five;
import org.devtcg.syncml.model.DatabaseMapping;
import org.devtcg.syncml.protocol.SyncItem;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.ContentURI;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

public class MusicMapping implements DatabaseMapping
{
	private static final String TAG = "MusicMapping";
	
	protected Handler mHandler;
	protected ContentResolver mContent;
	protected long mSourceId;
	
	protected int mCounter = 1;
	
	protected long mLastAnchor;
	protected long mNextAnchor;

	/**
	 * Temporary mapping id from server to client database identifiers.  This
	 * is necessary because certain elements refer to others by id and the server can't be
	 * bothered to synchronize its updates with our Map commands.
	 */
	protected HashMap<String, Long> mArtistMap = new HashMap<String, Long>();
	protected HashMap<String, Long> mAlbumMap = new HashMap<String, Long>();

	/**
	 * Total number of items synchronizing from server.
	 */
	protected int mNumChanges;
	
	private static final String mimePrefix = "application/x-fivedb-";
	
	public MusicMapping(ContentResolver content, Handler handler, long sourceId, long lastAnchor)
	{
		mContent = content;
		mHandler = handler;
		mSourceId = sourceId;
		mLastAnchor = lastAnchor;
		mNextAnchor = System.currentTimeMillis() / 1000;
		
		if (mNextAnchor <= mLastAnchor)
			throw new IllegalArgumentException("Last anchor may not meet or exceed the current time");
	}

	public String getName()
	{
		return "music";
	}

	public String getType()
	{
		return "application/x-fivedb";
	}

	public long getLastAnchor()
	{
		return mLastAnchor;
	}

	public long getNextAnchor()
	{
		return mNextAnchor;
	}

	public void setLastAnchor(long anchor)
	{
	}
	
	public void setNextAnchor(long anchor)
	{
		/* TODO: This shouldn't be part of the interface. */
	}

	public void beginSyncLocal(int code, long last, long next)
	{
		Log.i(TAG, "starting sync, code=" + code);

		/* Slow refresh from server: delete all our local content first. */
		if (code == 210)
		{
			mContent.delete(Five.Content.CONTENT_URI, null, null);
			mContent.delete(Five.Music.Artists.CONTENT_URI, null, null);
			mContent.delete(Five.Music.Albums.CONTENT_URI, null, null);
			mContent.delete(Five.Music.Songs.CONTENT_URI, null, null);
		}
	}

	public void beginSyncRemote(int numChanges)
	{
		Log.i(TAG, "Preparing to receive " + numChanges + " changes...");
		mNumChanges = numChanges;
	}

	public void endSync(boolean updateAnchor)
	{
		if (updateAnchor == true)
		{
			/* Successful sync, yay! */
			ContentValues v = new ContentValues();
			v.put(Five.Sources.REVISION, getNextAnchor());
			mContent.update(Five.Sources.CONTENT_URI.addId(mSourceId), v, null, null);

			/* XXX: This does nothing... */
			mLastAnchor = getNextAnchor();
		}
		
		mArtistMap.clear();
		mAlbumMap.clear();
	}

	public int insert(SyncItem item)
	{
		String mime = item.getMimeType();
		int typeIndex = mime.indexOf(mimePrefix);

		if (typeIndex != 0)
		{
			Log.e(TAG, "Unknown mime type: " + mime);
			return 400;
		}

		String format = mime.substring(typeIndex + mimePrefix.length());
		
		Log.i(TAG, "Inserting item (" + item.getMimeType() + "): " + item.getSourceId());
		MetaDataFormat meta = new MetaDataFormat(item.getData());

		ContentURI uri = null;
		ContentValues values = new ContentValues();

		if (format.equals("artist") == true)
		{
			values.put(Five.Music.Artists.NAME, meta.getValue("N"));			
//			values.put(Five.Music.Artists.GENRE, meta.getValue("GENRE"));
//			values.put(Five.Music.Artists.PHOTO_ID, meta.getValue("ARTWORK"));

			uri = mContent.insert(Five.Music.Artists.CONTENT_URI, values);

			if (uri != null)
				mArtistMap.put(item.getSourceId(), uri.getPathLeafId());
		}
		else if (format.equals("album") == true)
		{
			values.put(Five.Music.Albums.NAME, meta.getValue("N"));

			if (meta.hasValue("ARTIST_GUID") == true)
				values.put(Five.Music.Albums.ARTIST_ID, mArtistMap.get(meta.getValue("ARTIST_GUID")));
			else
				values.put(Five.Music.Albums.ARTIST_ID, meta.getValue("ARTIST"));

			uri = mContent.insert(Five.Music.Albums.CONTENT_URI, values);

			if (uri != null)
				mAlbumMap.put(item.getSourceId(), uri.getPathLeafId());
		}
		else if (format.equals("song") == true)
		{
			/* Create a media entry. */
			ContentValues cvalues = new ContentValues();
			cvalues.put(Five.Content.SIZE, meta.getValue("SIZE"));
			cvalues.put(Five.Content.SOURCE_ID, mSourceId);

			ContentURI curi = mContent.insert(Five.Content.CONTENT_URI.addId(mSourceId), cvalues);

			if (curi == null)
			{
				Log.e(TAG, "Failed to insert content");
				return 400;
			}

			/* And the meta data... */
			values.put(Five.Music.Songs.TITLE, meta.getValue("N"));

			if (meta.hasValue("ARTIST_GUID") == true)
				values.put(Five.Music.Songs.ARTIST_ID, mArtistMap.get(meta.getValue("ARTIST_GUID")));
			else
				values.put(Five.Music.Songs.ARTIST_ID, meta.getValue("ARTIST"));

			if (meta.hasValue("ALBUM_GUID") == true)
				values.put(Five.Music.Songs.ALBUM_ID, mAlbumMap.get(meta.getValue("ALBUM_GUID")));
			else
				values.put(Five.Music.Songs.ALBUM_ID, meta.getValue("ALBUM"));

			values.put(Five.Music.Songs.LENGTH, meta.getValue("LENGTH"));

			values.put(Five.Music.Songs.CONTENT_ID, meta.getValue("CONTENT"));
			values.put(Five.Music.Songs.CONTENT_SOURCE_ID, mSourceId);

			uri = mContent.insert(Five.Music.Songs.CONTENT_URI, values);

			if (uri == null)
			{
				/* TODO: Rollback the inserted content entry. */
			}
		}
		else
		{
			Log.e(TAG, "Unknown mime type: " + mime);
			return 400;
		}
		
		if (uri == null)
		{
			Log.e(TAG, "Failed to insert meta data");
			return 400;
		}
		
		item.setTargetId(uri.getPathLeafId());

		Message msg = mHandler.obtainMessage(MetaService.MSG_UPDATE_PROGRESS, mCounter++, mNumChanges);
		mHandler.sendMessage(msg);

		return 201;
	}

	public int update(SyncItem item)
	{
		return 0;
	}
	
	public int delete(SyncItem item)
	{
		return 0;
	}
	
	private static class MetaDataFormat
	{
		private HashMap<String, String> mData =
		  new HashMap<String, String>();

		public MetaDataFormat(String data)
		{
			Scanner scanner = new Scanner(data);
			
			while (scanner.hasNextLine() == true)
			{
				String line = scanner.nextLine();
				
				String keyvalue[] = line.split(":", 2);
				
				if (keyvalue.length < 2)
					throw new IllegalArgumentException("Parse error on line '" + line + "'");
				
				String old = mData.put(keyvalue[0], keyvalue[1]);
				
				if (old != null)
					Log.d(TAG, "Encountered unusual meta data input for key '" + keyvalue[0] + "' while parsing: " + data);
			}
			
			if (mData.isEmpty() == true)
				throw new IllegalArgumentException("No keys found in meta data");
		}

		public boolean hasValue(String key)
		{
			return mData.containsKey(key);
		}

		public String getValue(String key)
		{
			return mData.get(key);
		}
	}
}
