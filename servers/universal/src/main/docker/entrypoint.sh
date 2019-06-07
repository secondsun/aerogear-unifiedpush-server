#!/bin/bash

if [-z "$POSTGRES_USER"]; then
  /opt/jboss/wildfly/bin/jboss-cli.sh --file=/opt/jboss/wildfly/bin/create-ups-postgres-ds.cli
else
  /opt/jboss/wildfly/bin/jboss-cli.sh --file=/opt/jboss/wildfly/bin/create-ups-h2-inmemory-ds.cli
fi

/opt/jboss/wildfly/bin/standalone.sh -b 0.0.0.0
