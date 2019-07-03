/*
 * Copyright (C) Sportradar AG. See LICENSE for full license governing this code
 */

package com.sportradar.unifiedodds.sdk.caching;

import com.sportradar.unifiedodds.sdk.custombetentities.AvailableSelections;
import com.sportradar.unifiedodds.sdk.custombetentities.Calculation;
import com.sportradar.unifiedodds.sdk.custombetentities.Selection;
import com.sportradar.unifiedodds.sdk.entities.FixtureChange;
import com.sportradar.unifiedodds.sdk.exceptions.internal.CommunicationException;
import com.sportradar.utils.URN;

import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Created on 26/10/2017.
 * // TODO @eti: Javadoc
 */
public interface DataRouterManager {
    void requestSummaryEndpoint(Locale locale, URN id, CacheItem requester) throws CommunicationException;

    void requestFixtureEndpoint(Locale locale, URN id, boolean useCachedProvider, CacheItem requester) throws CommunicationException;

    void requestDrawSummary(Locale locale, URN id, CacheItem requester) throws CommunicationException;

    void requestDrawFixture(Locale locale, URN id, CacheItem requester) throws CommunicationException;

    void requestAllTournamentsForAllSportsEndpoint(Locale locale) throws CommunicationException;

    void requestAllSportsEndpoint(Locale locale) throws CommunicationException;

    void requestAllLotteriesEndpoint(Locale locale) throws CommunicationException;

    List<URN> requestEventsFor(Locale locale, URN tournamentId) throws CommunicationException;

    List<URN> requestLotterySchedule(Locale locale, URN lotteryId, CacheItem requester) throws CommunicationException;

    List<URN> requestEventsFor(Locale locale, Date date) throws CommunicationException;

    void requestPlayerProfileEndpoint(Locale locale, URN id, CacheItem requester) throws CommunicationException;

    void requestCompetitorEndpoint(Locale locale, URN id, CacheItem requester) throws CommunicationException;

    void requestSimpleTeamEndpoint(Locale locale, URN id, CacheItem requester) throws CommunicationException;

    List<URN> requestSeasonsFor(Locale locale, URN tournamentID) throws CommunicationException;

    void requestEventTimelineEndpoint(Locale locale, URN id, CacheItem requester) throws CommunicationException;

    void requestSportCategoriesEndpoint(Locale locale, URN id, CacheItem requester) throws CommunicationException;

    AvailableSelections requestAvailableSelections(URN id) throws CommunicationException;

    Calculation requestCalculateProbability(List<Selection> selections) throws CommunicationException;

    List<FixtureChange> requestFixtureChanges(Locale locale) throws CommunicationException;

    List<URN> requestListSportEvents(Locale locale, int startIndex, int limit) throws CommunicationException;
}
