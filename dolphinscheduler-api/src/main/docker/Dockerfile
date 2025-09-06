#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

FROM eclipse-temurin:8-jre

ENV DOCKER true
ENV TZ Asia/Shanghai
ENV DOLPHINSCHEDULER_HOME /opt/dolphinscheduler
ENV SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:${DOLPHINSCHEDULER_HOME}/ui/

WORKDIR $DOLPHINSCHEDULER_HOME
# kubectl
RUN curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" && \
    chmod +x kubectl && \
    mv kubectl /usr/local/bin/

RUN rm -rf $DOLPHINSCHEDULER_HOME/ui
ADD ./target/api-server $DOLPHINSCHEDULER_HOME

EXPOSE 12345 25333

CMD [ "/bin/bash", "./bin/start.sh" ]
