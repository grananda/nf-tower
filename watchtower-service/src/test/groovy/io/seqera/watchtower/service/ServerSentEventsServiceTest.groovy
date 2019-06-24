/*
 * Copyright (c) 2019, Seqera Labs.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */

package io.seqera.watchtower.service

import grails.gorm.transactions.Transactional
import io.micronaut.http.sse.Event
import io.micronaut.test.annotation.MicronautTest
import io.reactivex.Flowable
import io.reactivex.subscribers.TestSubscriber
import io.seqera.watchtower.Application
import io.seqera.watchtower.pogo.exceptions.NonExistingFlowableException
import io.seqera.watchtower.util.AbstractContainerBaseTest

import javax.inject.Inject
import java.time.Duration

@MicronautTest(application = Application.class)
@Transactional
class ServerSentEventsServiceTest extends AbstractContainerBaseTest {

    @Inject
    ServerSentEventsServiceImpl serverSentEventsService


    void "create a flowable and retrieve it"() {
        given: 'a key for the flowable'
        String key = '1'

        when: 'create the flowable given a key'
        serverSentEventsService.createFlowable(key, Duration.ofMinutes(1))

        then: 'the flowable can be retrieved'
        serverSentEventsService.getThrottledFlowable(key, Duration.ofMinutes(0))
    }

    void "create a flowable and publish some data for it"() {
        given: 'a key for the flowable'
        String key = '2'

        and: 'create the flowable'
        serverSentEventsService.createFlowable(key, Duration.ofMinutes(1))

        and: 'subscribe to the flowable in order to retrieve the data'
        TestSubscriber subscriber = serverSentEventsService.getThrottledFlowable(key, Duration.ofMinutes(0)).test()

        when: 'publish some data for it'
        Event event = Event.of([text: 'Data published'])
        serverSentEventsService.publishEvent(key, event)

        then: 'the subscriber has obtained the data correctly'
        subscriber.assertValueCount(1)
        subscriber.events.first()[0].data.text == 'Data published'
    }

    void "create a flowable and complete it"() {
        given: 'a key for the flowable'
        String key = '3'

        and: 'create the flowable'
        serverSentEventsService.createFlowable(key, Duration.ofMinutes(1))

        and: 'subscribe to the flowable in order to retrieve the data'
        TestSubscriber subscriber = serverSentEventsService.getThrottledFlowable(key, Duration.ofMinutes(0)).test()

        when: 'complete the flowable'
        serverSentEventsService.completeFlowable(key)

        then: 'the flowable has been completed'
        subscriber.assertComplete()

        when: 'try to get the flowable again'
        serverSentEventsService.getThrottledFlowable(key, Duration.ofMinutes(0))

        then: 'the flowable is no longer present'
        thrown(NonExistingFlowableException)
    }

    void "create a flowable and throttle the events"() {
        given: 'a key for the flowable'
        String key = '4'

        and: 'set a short throttle time'
        Duration throttleTime = Duration.ofMillis(500)

        and: 'create the flowable'
        serverSentEventsService.createFlowable(key, Duration.ofMinutes(1))

        and: 'subscribe to the flowable in order to retrieve data'
        TestSubscriber subscriber = serverSentEventsService.getThrottledFlowable(key, throttleTime).test()

        when: 'publish some data for it'
        serverSentEventsService.publishEvent(key, Event.of([text: 'Data published 1']))

        and: 'publish more data right after the previous one'
        serverSentEventsService.publishEvent(key, Event.of([text: 'Data published 2']))

        then: 'the subscriber has obtained only the first published data'
        subscriber.assertValueCount(1)
        subscriber.events.first()[0].data.text == 'Data published 1'

        when: 'make sure the throttle time has been surpassed'
        sleep(throttleTime.toMillis() + 100)

        then: 'the subscriber has obtained the last published data'
        subscriber.assertValueCount(2)
        subscriber.events.first()[1].data.text == 'Data published 2'
    }

    void "create a flowable and leave it idle until the timeout strikes"() {
        given: 'a key for the flowable'
        String key = '5'

        and: 'set a short idle timeout'
        Duration idleTimeout = Duration.ofMillis(300)

        and: 'create the flowable'
        serverSentEventsService.createFlowable(key, idleTimeout)

        and: 'subscribe to the flowable in order to retrieve data'
        TestSubscriber subscriber = serverSentEventsService.getThrottledFlowable(key, Duration.ofMinutes(0)).test()

        when: 'sleep until the timeout plus a prudential time to make sure it was reached'
        sleep(idleTimeout.toMillis() + 100)

        then: 'the flowable has been completed'
        subscriber.assertComplete()
    }

    void "create a heartbeat flowable and receive the herartbeat events"() {
        given: 'a heartbeat interval'
        Duration interval = Duration.ofMillis(250)

        and: 'the heatbeat flowable'
        Flowable heartbeatFlowable = serverSentEventsService.generateHeartbeatFlowable(interval, { Event.of([text: "Heartbeat ${it}"]) })

        when: 'subscribe to the flowable'
        TestSubscriber subscriber = heartbeatFlowable.test()

        and: 'sleep a prudential time to generate a heartbeat'
        sleep(interval.toMillis() + 1)

        then: 'the data has been generated'
        subscriber.assertValueCount(1)
        subscriber.events.first()[0].data.text == 'Heartbeat 0'

        and: 'sleep a prudential time to generate another heartbeat'
        sleep(interval.toMillis() + 1)

        then: 'the data has been generated'
        subscriber.assertValueCount(2)
        subscriber.events.first()[1].data.text == 'Heartbeat 1'
    }

}
