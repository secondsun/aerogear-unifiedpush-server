package org.jboss.aerogear.unifiedpush.message.jms;

import javax.inject.Qualifier;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for messages that contain results of a push senders send method.  This is used by CDI to route events
 * from the push senders to handlers that can schedule any follow up work.
 */
@Qualifier
@Target({ ElementType.PARAMETER, ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
public @interface PushSenderResult {
}
