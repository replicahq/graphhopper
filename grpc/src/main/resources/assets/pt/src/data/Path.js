/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import WalkLeg from "./WalkLeg.js";
import PtLeg from "./PtLeg.js";
import { LegMode } from "./Leg.js";
import Waypoint from "./Waypoint.js";

export default class Path {
  get waypoints() {
    return this._waypoints;
  }

  get legs() {
    return this._legs;
  }

  get departureTime() {
    return this._departureTime;
  }

  get arrivalTime() {
    return this._arrivalTime;
  }

  get durationInMinutes() {
    return moment(this._arrivalTime).diff(this._departureTime, "minutes");
  }

  get fare() {
    return this._fare;
  }

  get transfers() {
    return this._waypoints.length - 4;
  }

  get isSelected() {
    return this._isSelected;
  }

  set isSelected(value) {
    this._isSelected = value;
  }

  get isPossible() {
    return this._isPossible;
  }

  constructor(apiPath) {
    this._initializeSimpleValues(apiPath);
    this._initializeLegsAndTransfers(apiPath);
    this._isPossible = this._checkIfPossible();
  }

  _initializeSimpleValues(apiPath) {
    this._isSelected = false;
    this._departureTime = apiPath.getLegsList()[0].getDepartureTime().toDate();
    this._arrivalTime = apiPath.getLegsList()[apiPath.getLegsList().length - 1].getArrivalTime().toDate();
    this._fare = apiPath.fare;
  }

  _initializeLegsAndTransfers(apiPath) {
    this._legs = [];
    this._waypoints = [];
    // function merge(array1, array2) {
    //   console.log("merge");
    //   console.log(array1);
    //   console.log(array2);
    //   var c = [];
    //   for (var i = 0; i < array1.length; i++) {
    //     c.push(array1[i]);
    //     c.push(array2[i]);
    //   }
    //   console.log(c);
    //   return c;
    // }
    apiPath.getLegsList().forEach((apiLeg, i) => {
      if (!apiLeg.hasTransitMetadata()) {
        apiLeg.type = apiLeg.getMode();
        this.legs.push(new WalkLeg(apiLeg));
      } else {
        apiLeg.type = LegMode.PT;
        this.legs.push(new PtLeg(apiLeg));
      }
      if (i > 0) {
        this._waypoints.push(new Waypoint(apiPath.getLegsList()[i - 1], apiLeg));
      } else this._waypoints.push(new Waypoint(null, apiLeg));
      if (i === apiPath.getLegsList().length - 1)
        this.waypoints.push(new Waypoint(apiLeg, null));
    });
  }

  _checkIfPossible() {
    let isPossible = true;
    this._waypoints.forEach(waypoint => {
      if (!waypoint.isPossible) isPossible = false;
    });
    return isPossible;
  }
}
