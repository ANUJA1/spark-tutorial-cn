#!/usr/bin/env bash

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# This script loads spark-env.sh if it exists, and ensures it is only loaded once.
# spark-env.sh is loaded from SPARK_CONF_DIR if set, or within the current directory's
# conf/ subdirectory.

# 这个脚本有两个主要逻辑：只允许加载一次、选择判断config目录
if [ -z "$SPARK_ENV_LOADED" ]; then
  export SPARK_ENV_LOADED=1

  # Returns the parent of the directory this script lives in.
  # 如果调用者是start-master.sh，这里的$0是指start-master.sh，因为他是用.来嵌入执行的，包括可以读取start-master脚本里的变量
  parent_dir="$(cd `dirname $0`/..; pwd)"

  # 如果有SPARK_CONF_DIR定义且非null则取其值，否则用当前目录下conf目录，例如从start-master进来，则是sbin/conf目录
  # 注意：SPARK_CONF_DIR是在spark-config.sh里定义的，而在执行完spark-config后，如果执行spark-daemon.sh，这个脚本接受通过--config参数设置其值，这一点可能会导致变量值前后不一致的情况（这里主要指一个脚本中前后执行中会写出不同的conf目录地址，例如start-master）,应该避免@TODO
  use_conf_dir=${SPARK_CONF_DIR:-"$parent_dir/conf"}

  if [ -f "${use_conf_dir}/spark-env.sh" ]; then
    # Promote all variable declarations to environment (exported) variables
    # 使用set -a相当于变量用declare -x/exported声明，保证能够被不同shell上下文读取
    set -a
    . "${use_conf_dir}/spark-env.sh"
    set +a
  fi
fi
