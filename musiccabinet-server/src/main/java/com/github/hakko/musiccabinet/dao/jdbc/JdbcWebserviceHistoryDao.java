package com.github.hakko.musiccabinet.dao.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

import javax.sql.DataSource;

import org.joda.time.DateTime;
import org.joda.time.Days;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.github.hakko.musiccabinet.dao.MusicDao;
import com.github.hakko.musiccabinet.dao.WebserviceHistoryDao;
import com.github.hakko.musiccabinet.domain.model.library.WebserviceInvocation;
import com.github.hakko.musiccabinet.domain.model.library.WebserviceInvocation.Calltype;
import com.github.hakko.musiccabinet.domain.model.music.Album;
import com.github.hakko.musiccabinet.domain.model.music.Artist;
import com.github.hakko.musiccabinet.domain.model.music.Track;

public class JdbcWebserviceHistoryDao implements JdbcTemplateDao, WebserviceHistoryDao {

	private JdbcTemplate jdbcTemplate;
	private MusicDao musicDao;
	
	@Override
	public void logWebserviceInvocation(WebserviceInvocation wi) {
		logWebserviceInvocation(wi, false);
	}

	@Override
	public void quarantineWebserviceInvocation(WebserviceInvocation wi) {
		logWebserviceInvocation(wi, true);
	}

	private void logWebserviceInvocation(WebserviceInvocation wi, boolean quarantine) {
		Integer artistId = null, trackId = null, albumId = null;
		if (wi.getTrack() != null) {
			trackId = musicDao.getTrackId(wi.getTrack());
		} else if (wi.getAlbum() != null) {
			albumId = musicDao.getAlbumId(wi.getAlbum());
		} else if (wi.getArtist() != null) {
			artistId = musicDao.getArtistId(wi.getArtist());
		}
		
		StringBuilder deleteSql = new StringBuilder(
				"delete from library.webservice_history where calltype_id = "
						+ wi.getCallType().getDatabaseId());
		if (artistId != null)
			deleteSql.append(" and artist_id = " + artistId);
		if (albumId != null) 
			deleteSql.append(" and album_id = " + albumId);
		if (trackId != null)
			deleteSql.append(" and track_id = " + trackId);
		if (wi.getPage() != null)
			deleteSql.append(" and page = " + wi.getPage());
		jdbcTemplate.execute(deleteSql.toString());

		String columns = "artist_id, album_id, track_id, calltype_id, page";
		String values = artistId + ", " + albumId + ", " + trackId + ", " 
				+ wi.getCallType().getDatabaseId() + ", " + wi.getPage();
		if (quarantine) { // then log it as invoked one month from now
			jdbcTemplate.update("insert into library.webservice_history (" + columns
					+ ", invocation_time) values (" + values + ",?)", 
					new DateTime().plusMonths(1).toDate());
		} else {
			jdbcTemplate.update("insert into library.webservice_history (" + columns
					+ ") values (" + values + ")");
		}
	}
	
	/*
	 * Implementation of DAO interface method to check if a certain invocation
	 * would be allowed.
	 * 
	 * Delegates decision to relevant method/query depending on type of invocation.
	 */
	@Override
	public boolean isWebserviceInvocationAllowed(WebserviceInvocation wi) {
		if (wi.getPage() != null) {
			return isWebserviceInvocationAllowed(wi.getCallType(), wi.getPage());
		} else if (wi.getTrack() != null) {
			return isWebserviceInvocationAllowed(wi.getCallType(), wi.getTrack());
		} else if (wi.getAlbum() != null) {
			return isWebserviceInvocationAllowed(wi.getCallType(), wi.getAlbum());
		} else if (wi.getArtist() != null) {
			return isWebserviceInvocationAllowed(wi.getCallType(), wi.getArtist());
		} else {
			 // for compiler compliance, actually unreachable
			return false;
		}
	}

	protected boolean isWebserviceInvocationAllowed(Calltype callType, short page) {
		String sql = "select max(invocation_time) from library.webservice_history"
			+ " where calltype_id = " + callType.getDatabaseId() 
			+ " and page = " + page;
		Timestamp lastInvocation = jdbcTemplate.queryForObject(sql, Timestamp.class);
		return isWebserviceInvocationAllowed(callType, lastInvocation);
	}

	protected boolean isWebserviceInvocationAllowed(Calltype callType, Artist artist) {
		String sql = "select max(invocation_time) from library.webservice_history"
			+ " inner join music.artist on music.artist.id = library.webservice_history.artist_id"
			+ " where calltype_id = " + callType.getDatabaseId()
			+ " and music.artist.artist_name = upper(?)";
		Timestamp lastInvocation = jdbcTemplate.queryForObject(sql, 
				new Object[]{artist.getName()}, Timestamp.class);
		return isWebserviceInvocationAllowed(callType, lastInvocation);
	}

	protected boolean isWebserviceInvocationAllowed(Calltype callType, Track track) {
		String sql = "select max(invocation_time) from library.webservice_history"
			+ " inner join music.track on music.track.id = library.webservice_history.track_id"
			+ " inner join music.artist on music.artist.id = music.track.artist_id"
			+ " where calltype_id = " + callType.getDatabaseId()
			+ " and music.artist.artist_name = upper(?) and music.track.track_name = upper(?)";
		Timestamp lastInvocation = jdbcTemplate.queryForObject(sql, new Object[]{
				track.getArtist().getName(), track.getName()}, Timestamp.class);
		return isWebserviceInvocationAllowed(callType, lastInvocation);
	}

	protected boolean isWebserviceInvocationAllowed(Calltype callType, Album album) {
		String sql = "select max(invocation_time) from library.webservice_history"
			+ " inner join music.album on music.album.id = library.webservice_history.album_id"
			+ " inner join music.artist on music.artist.id = music.album.artist_id"
			+ " where calltype_id = " + callType.getDatabaseId()
			+ " and music.artist.artist_name = upper(?) and music.album.album_name = upper(?)";
		Timestamp lastInvocation = jdbcTemplate.queryForObject(sql, new Object[]{
				album.getArtist().getName(), album.getName()}, Timestamp.class);
		return isWebserviceInvocationAllowed(callType, lastInvocation);
	}

	private boolean isWebserviceInvocationAllowed(Calltype callType, Timestamp lastInvocation) {
		boolean oldEnough;
		if (lastInvocation == null) {
			oldEnough = true;
		} else {
			DateTime lastInvocationDateTime = new DateTime(lastInvocation.getTime());
			Days daysBetween = Days.daysBetween(lastInvocationDateTime, new DateTime());
			oldEnough = daysBetween.getDays() > callType.getDaysToCache();
		}
		return oldEnough;
	}

	/*
	 * Group artists in local library by last update time from last.fm, and return
	 * the 1/30th that were updated longest ago.
	 */
	protected List<Artist> getArtistsWithOldestInvocations(Calltype callType) {
		String sql = "select a.artist_name_capitalization from ("
			+ "  select artist_id, ntile(30) over (order by invocation_time) "
			+ "   from library.webservice_history where calltype_id = " + callType.getDatabaseId()
			+ " ) ntile" 
			+ " inner join music.artist a on ntile.artist_id = a.id"
			+ " and ntile.ntile = 1"
			+ " where a.id in "
			+ " (select t.artist_id from library.musicfile mf "
			+ "  inner join music.track t on mf.track_id = t.id)";
		
		List<Artist> artists = jdbcTemplate.query(sql, new RowMapper<Artist>() {
			@Override
			public Artist mapRow(ResultSet rs, int rowNum) throws SQLException {
				return new Artist(rs.getString(1));
			}
		});
		
		return artists;
	}

	/*
	 * Return artists found in local library, who's never been looked up from last.fm.
	 */
	protected List<Artist> getArtistsWithNoInvocations(Calltype callType) {
		String sql = "select artist_name_capitalization from music.artist where id in ("
				+ " select distinct t.artist_id from library.musicfile mf"
				+ " inner join music.track t on mf.track_id = t.id"
				+ " where not exists ("
				+ " select 1 from library.webservice_history where artist_id = t.artist_id "
				+ " and calltype_id = " + callType.getDatabaseId() + "))";
		
		List<Artist> artists = jdbcTemplate.query(sql, new RowMapper<Artist>() {
			@Override
			public Artist mapRow(ResultSet rs, int rowNum) throws SQLException {
				return new Artist(rs.getString(1));
			}
		});
		
		return artists;
	}

	/*
	 * Returns a concatenated list of artists from the local library, who:
	 * - have either never been looked up from last.fm
	 * - or have been looked up, but belong in the oldest ntile,
	 *   meaning it's time for them to get updated.
	 */
	@Override
	public List<Artist> getArtistsScheduledForUpdate(Calltype callType) {
		List<Artist> newArtists = getArtistsWithNoInvocations(callType);
		List<Artist> oldestArtists = getArtistsWithOldestInvocations(callType);
		
		if (!newArtists.isEmpty()) {
			oldestArtists.addAll(newArtists);
		}
		
		return oldestArtists;
	}
	
	@Override
	public JdbcTemplate getJdbcTemplate() {
		return jdbcTemplate;
	}
	
	public void setDataSource(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	public void setMusicDao(MusicDao musicDao) {
		this.musicDao = musicDao;
	}
	
}