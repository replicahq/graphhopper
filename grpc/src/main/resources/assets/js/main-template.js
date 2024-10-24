global.d3 = require('d3');
var YAML = require('js-yaml');
var Flatpickr = require('flatpickr');
require('flatpickr/dist/l10n');

var L = require('leaflet');
require('leaflet-contextmenu');
require('leaflet-loading');
require('leaflet.heightgraph');
var moment = require('moment');
require('./lib/leaflet_numbered_markers.js');

global.jQuery = require('jquery');
global.$ = global.jQuery;
require('./lib/jquery-ui-custom-1.12.0.min.js');
require('./lib/jquery.history.js');
require('./lib/jquery.autocomplete.js');

var ghenv = require("./config/options.js").options;
console.log(ghenv.environment);

var Router = require('../pt/src/grpc/grpc/src/main/proto/router_grpc_web_pb.js');
const wkt = require('wkt');

var GHInput = require('./graphhopper/GHInput.js');
var GHRequest = require('./graphhopper/GHRequest.js');
var host = ghenv.routing.host;
if (!host) {
    if (location.port === '') {
        host = location.protocol + '//' + location.hostname;
    } else {
        host = location.protocol + '//' + location.hostname + ":" + location.port;
    }
}

var AutoComplete = require('./autocomplete.js');
if (ghenv.environment === 'development') {
    var autocomplete = AutoComplete.prototype.createStub();
} else {
    var autocomplete = new AutoComplete(ghenv.geocoding.host, ghenv.geocoding.api_key);
}

var mapLayer = require('./map.js');
var nominatim = require('./nominatim.js');
var routeManipulation = require('./routeManipulation.js');
var gpxExport = require('./gpxexport.js');
var messages = require('./messages.js');
var translate = require('./translate.js');

var format = require('./tools/format.js');
var urlTools = require('./tools/url.js');
var tileLayers = require('./config/tileLayers.js');
if(ghenv.with_tiles)
   tileLayers.enableVectorTiles();

var debug = false;
var ghRequest = new GHRequest(host, ghenv.routing.api_key);
var bounds = {};
var metaVersionInfo;

// usage: log('inside coolFunc',this,arguments);
// http://paulirish.com/2009/log-a-lightweight-wrapper-for-consolelog/
if (global.window) {
    window.log = function () {
        log.history = log.history || [];   // store logs to an array for reference
        log.history.push(arguments);
        if (this.console && debug) {
            console.log(Array.prototype.slice.call(arguments));
        }
    };
}

$(document).ready(function (e) {
    // fixing cross domain support e.g in Opera
    jQuery.support.cors = true;

    gpxExport.addGpxExport(ghRequest);

    $("#flex-input-link").click(function() {
        $("#regular-input").toggle();
        $("#flex-input").toggle();
        // avoid default action, so use a different search button
        $("#searchButton").toggle();
        mapLayer.adjustMapSize();
    });
    $("#flex-example").click(function() {
         $("#flex-input-text").val("{\n  \"speed\": [\n    {\n      \"if\": \"road_class == SECONDARY\",\n      \"multiply_by\": \"0.9\"\n    }\n  ],\n  \"priority\": [\n    {\n      \"if\": \"road_class == SECONDARY\",\n      \"multiply_by\": \"0.9\"\n    }\n  ],\n  \"distance_influence\": \"70\"\n}");
         return false;
    });


    var sendCustomData = function() {
       mapLayer.clearElevation();
       mapLayer.clearLayers();
       flagAll();

       var infoDiv = $("#info");
       infoDiv.empty();
       infoDiv.show();
       var routeResultsDiv = $("<div class='route_results'/>");
       infoDiv.append(routeResultsDiv);
       routeResultsDiv.html('<img src="img/indicator.gif"/> Search Route ...');
       var inputText = $("#flex-input-text").val();
       if(inputText.length < 5) {
           routeResultsDiv.html("Routing configuration too short");
           return;
       }

       var fromPoint = ghRequest.from;
       var toPoint = ghRequest.to;
       if (!fromPoint.isResolved() || !toPoint.isResolved()) {
            routeResultsDiv.html("Unresolved points!");
            return;
       }

       var jsonModel;
       try {
         jsonModel = inputText.indexOf("{") == 0? JSON.parse(inputText) : YAML.safeLoad(inputText);
       } catch(ex) {
         routeResultsDiv.html("Cannot parse " + inputText + " " + ex);
         return;
       }

       var customRouteRequest = new Router.CustomRouteRequest();
       customRouteRequest.setProfile(ghRequest.api_params.profile);

       var from = new Router.Point();
       from.setLat(fromPoint.lat);
       from.setLon(fromPoint.lng);
       var to = new Router.Point();
       to.setLat(toPoint.lat);
       to.setLon(toPoint.lng);
       customRouteRequest.addPoints(from);
       customRouteRequest.addPoints(to);

       customRouteRequest.setCustomModel(JSON.stringify(jsonModel));

       customRouteRequest.setAlternateRouteMaxPaths($('#alt_route_max_paths').val());
       customRouteRequest.setAlternateRouteMaxWeightFactor($('#alt_route_max_weight_factor').val());
       customRouteRequest.setAlternateRouteMaxShareFactor($('#alt_route_max_share_factor').val());
       customRouteRequest.setReturnFullPathDetails(true);
       console.log(customRouteRequest.toObject());

        var router = new Router.RouterClient('/api');
        router.routeCustom(customRouteRequest, null, function(err, response) {
            if (err) {
                console.log("Error handling request!")
                console.log(err)
                console.log(customRouteRequest);
            } else {
                console.log(response.toObject());

                routeResultsDiv.html("");

                function createClickHandler(geoJsons, currentLayerIndex, tabHeader, oneTab, hasElevation, details) {
                    return function () {

                       var currentGeoJson = geoJsons[currentLayerIndex];
                       mapLayer.eachLayer(function (layer) {
                           // skip markers etc
                           if (!layer.setStyle)
                               return;

                           var doHighlight = layer.feature === currentGeoJson;
                           layer.setStyle(doHighlight ? highlightRouteStyle : alternativeRouteStye);
                           if (doHighlight) {
                               if (!L.Browser.ie && !L.Browser.opera)
                                   layer.bringToFront();
                           }
                       });

                       if (hasElevation) {
                           mapLayer.clearElevation();
                           mapLayer.addElevation(currentGeoJson, details);
                       }

                       headerTabs.find("li").removeClass("current");
                       routeResultsDiv.find("div").removeClass("current");

                       tabHeader.addClass("current");
                       oneTab.addClass("current");
                    };
                }
                var headerTabs = $("<ul id='route_result_tabs'/>");
                if (response.getPathsList().length > 1) {
                   routeResultsDiv.append(headerTabs);
                   routeResultsDiv.append("<div class='clear'/>");
                }

                // the routing layer uses the geojson properties.style for the style, see map.js
                var defaultRouteStyle = {color: "#00cc33", "weight": 5, "opacity": 0.6};
                var highlightRouteStyle = {color: "#00cc33", "weight": 6, "opacity": 0.8};
                var alternativeRouteStye = {color: "darkgray", "weight": 6, "opacity": 0.8};
                var geoJsons = [];
                var firstHeader;

                // Create buttons to toggle between SI and imperial units.
                var createUnitsChooserButtonClickHandler = function (useMiles) {
                   return function () {
                       mapLayer.updateScale(useMiles);
                       ghRequest.useMiles = useMiles;
                       resolveAll();
                       if (ghRequest.route.isResolved())
                         routeLatLng(ghRequest);
                   };
                };

                for (var pathIndex = 0; pathIndex < response.getPathsList().length; pathIndex++) {
                    var tabHeader = $("<li>").append((pathIndex + 1) + "<img class='alt_route_img' src='img/alt_route.png'/>");
                    if (pathIndex === 0)
                       firstHeader = tabHeader;

                    headerTabs.append(tabHeader);
                    var path = response.getPathsList()[pathIndex];
                    var style = (pathIndex === 0) ? defaultRouteStyle : alternativeRouteStye;

                    console.log(wkt.parse(path.getPoints()))
                    var geojsonFeature = {
                       "type": "Feature",
                       "geometry": wkt.parse(path.getPoints()),
                       "properties": {
                           "style": style,
                           name: "route",
                       }
                    };

                    geoJsons.push(geojsonFeature);
                    mapLayer.addDataToRoutingLayer(geojsonFeature);
                    var oneTab = $("<div class='route_result_tab'>");
                    routeResultsDiv.append(oneTab);
                    tabHeader.click(createClickHandler(geoJsons, pathIndex, tabHeader, oneTab, ghRequest.hasElevation(), path.details));

                    var routeInfo = $("<div class='route_description'>");

                    var kmButton = $("<button class='plain_text_button " + (ghRequest.useMiles ? "gray" : "") + "'>");
                    kmButton.text(translate.tr2("km_abbr"));
                    kmButton.click(createUnitsChooserButtonClickHandler(false));

                    var miButton = $("<button class='plain_text_button " + (ghRequest.useMiles ? "" : "gray") + "'>");
                    miButton.text(translate.tr2("mi_abbr"));
                    miButton.click(createUnitsChooserButtonClickHandler(true));

                    var buttons = $("<span style='float: right;'>");
                    buttons.append(kmButton);
                    buttons.append('|');
                    buttons.append(miButton);

                    routeInfo.append(buttons);

                    routeInfo.append($("<div style='clear:both'/>"));
                    oneTab.append(routeInfo);
               }
               // already select best path
               firstHeader.click();

               mapLayer.adjustMapSize();

               $('.defaulting').each(function (index, element) {
                   $(element).css("color", "black");
               });
            }
        })
    }


/*
       $.ajax({
           url: "/route-custom",
           type: "POST",
           contentType: 'application/json; charset=utf-8',
           dataType: "json",
           data: request,
           success: createRouteCallback(ghRequest, routeResultsDiv, "", true),
           error: function(err) {
               routeResultsDiv.html("Error response: cannot process input");
               var json = JSON.parse(err.responseText);
               createRouteCallback(ghRequest, routeResultsDiv, "", true)(json);
           }
        });
    };
*/

    $("#flex-input-text").keydown(function (e) {
        // CTRL+Enter
        if (e.ctrlKey && e.keyCode == 13) sendCustomData();
    });
    $("#flex-search-button").click(sendCustomData);

    if (isProduction())
        $('#hosting').show();

    var History = window.History;
    if (History.enabled) {
        History.Adapter.bind(window, 'statechange', function () {
            // No need for workaround?
            // Chrome and Safari always emit a popstate event on page load, but Firefox doesn’t
            // https://github.com/defunkt/jquery-pjax/issues/143#issuecomment-6194330

            var state = History.getState();
            console.log(state);
            initFromParams(state.data, true);
        });
    }

    $('#locationform').submit(function (e) {
        // no page reload
        e.preventDefault();
        mySubmit();
    });

    var urlParams = urlTools.parseUrlWithHisto();

    if(urlParams.flex)
        $("#flex-input-link").click();

    var customURL = urlParams.load_custom;
    if(customURL && ghenv.environment === 'development')
        $.ajax(customURL).
            done(function(data) { $("#flex-input-text").val(data); $("#flex-input-link").click(); }).
            fail(function(err)  { console.log("Cannot load custom URL " + customURL); });

    var router = new Router.RouterClient('/api');
    router.info(new Router.InfoRequest(), null, function(err, response) {
        if (err) {
           console.log(err);
           $('#error').html('GraphHopper API offline? <a href="http://graphhopper.com/maps/">Refresh</a>' + '<br/>Status: ' + err.statusText + '<br/>' + host);

           bounds = {
               "minLon": -180,
               "minLat": -90,
               "maxLon": 180,
               "maxLat": 90
           };
           nominatim.setBounds(bounds);
           mapLayer.initMap(bounds, setStartCoord, setIntermediateCoord, setEndCoord, urlParams.layer, urlParams.use_miles);
        } else {
           bbox = response.toObject()['bboxList'];
           // init bounding box from getInfo result
           bounds.initialized = true;
           bounds.minLon = bbox[0];
           bounds.minLat = bbox[1];
           bounds.maxLon = bbox[2];
           bounds.maxLat = bbox[3];
           nominatim.setBounds(bounds);
           var profilesDiv = $("#profiles");

           function createButton(profile, hide) {
               var profileName = profile;
               var button = $("<button class='vehicle-btn' title='" + profileName + "'/>");
               if (hide)
                   button.hide();

               button.attr('id', profileName);
               button.html("<img src='img/" + profileName.toLowerCase() + ".png' alt='" + profileName + "'></img>");
               button.click(function () {
                   ghRequest.setProfile(profileName);
                   ghRequest.removeLegacyParameters();
                   resolveAll();
                   if (ghRequest.route.isResolved())
                     routeLatLng(ghRequest);
               });
               return button;
           }


           var profiles = ["car", "foot", "bike", "truck", "small_truck"];
           ghRequest.profiles = profiles;
           var showAllProfiles = true;

           var numVehiclesWhenCollapsed = 5;
           var hiddenVehicles = [];
           for (var i = 0; i < profiles.length; ++i) {
               var btn = createButton(profiles[i], !showAllProfiles && i >= numVehiclesWhenCollapsed);
               profilesDiv.append(btn);
               if (i >= numVehiclesWhenCollapsed)
                   hiddenVehicles.push(btn);
           }

           if (!showAllProfiles && profiles.length > numVehiclesWhenCollapsed) {
               var moreBtn = $("<a id='more-vehicle-btn'> ...</a>").click(function () {
                   moreBtn.hide();
                   for (var i in hiddenVehicles) {
                       hiddenVehicles[i].show();
                   }
               });
               profilesDiv.append($("<a class='vehicle-info-link' href='https://docs.graphhopper.com/#section/Map-Data-and-Routing-Profiles/OpenStreetMap'>?</a>"));
               profilesDiv.append(moreBtn);
           }

           $("button#" + profiles[0]).addClass("selectprofile");

           mapLayer.initMap(bounds, setStartCoord, setIntermediateCoord, setEndCoord, urlParams.layer, urlParams.use_miles);

           // execute query
           initFromParams(urlParams, true);

           checkInput();
        }
    });

    var language_code = urlParams.locale && urlParams.locale.split('-', 1)[0];
    if (language_code != 'en') {
        // A few language codes are different in GraphHopper and Flatpickr.
        var flatpickr_locale;
        switch (language_code) {
            case 'ca':  // Catalan
                flatpickr_locale = 'cat';
                break;
            case 'el':  // Greek
                flatpickr_locale = 'gr';
                break;
            default:
                flatpickr_locale = language_code;
        }
        if (Flatpickr.l10ns.hasOwnProperty(flatpickr_locale)) {
            Flatpickr.localize(Flatpickr.l10ns[flatpickr_locale]);
        }
    }

    $(window).resize(function () {
        mapLayer.adjustMapSize();
    });
    $("#locationpoints").sortable({
        items: ".pointDiv",
        cursor: "n-resize",
        containment: "parent",
        handle: ".pointFlag",
        update: function (event, ui) {
            var origin_index = $(ui.item[0]).data('index');
            sortable_items = $("#locationpoints > div.pointDiv");
            $(sortable_items).each(function (index) {
                var data_index = $(this).data('index');
                if (origin_index === data_index) {
                    //log(data_index +'>'+ index);
                    ghRequest.route.move(data_index, index);
                    if (!routeIfAllResolved())
                        checkInput();
                    return false;
                }
            });
        }
    });

    $('#locationpoints > div.pointAdd').click(function () {
        ghRequest.route.add(new GHInput());
        checkInput();
    });

    checkInput();
});

function profileDisplayName(profileName) {
   // custom profile names like 'my_car' cannot be translated and will be returned like 'web.my_car', so we remove
   // the 'web.' prefix in this case
   return translate.tr(profileName).replace("web.", "");
}

/**
 * Takes an array and returns another array with the same elements but sorted such that all elements matching the given
 * condition come first.
 */
function moveToFront(arr, condition) {
    return arr.filter(condition).concat(arr.filter(function (e) { return !condition(e); }))
}

function initFromParams(params, doQuery) {
    ghRequest.init(params);

    var flatpickr = new Flatpickr(document.getElementById("input_date_0"), {
        defaultDate: new Date(),
        allowInput: true, /* somehow then does not sync!? */
        minuteIncrement: 15,
        time_24hr: true,
        enableTime: true
    });
    if (ghRequest.isPublicTransit())
        $(".time_input").show();
    else
        $(".time_input").hide();
    if (ghRequest.getEarliestDepartureTime()) {
        flatpickr.setDate(ghRequest.getEarliestDepartureTime());
    }

    var count = 0;
    var singlePointIndex;
    if (params.point)
        for (var key = 0; key < params.point.length; key++) {
            if (params.point[key] !== "") {
                count++;
                singlePointIndex = key;
            }
        }

    var routeNow = params.point && count >= 2;
    if (routeNow) {
        resolveCoords(params.point, doQuery);
    } else if (params.point && count === 1) {
        ghRequest.route.set(params.point[singlePointIndex], singlePointIndex, true);
        resolveIndex(singlePointIndex).done(function () {
            mapLayer.focus(ghRequest.route.getIndex(singlePointIndex), 15, singlePointIndex);
        });
    }
}

function resolveCoords(pointsAsStr, doQuery) {
    for (var i = 0, l = pointsAsStr.length; i < l; i++) {
        var pointStr = pointsAsStr[i];
        var coords = ghRequest.route.getIndex(i);
        if (!coords || pointStr !== coords.input || !coords.isResolved())
            ghRequest.route.set(pointStr, i, true);
    }

    checkInput();

    if (ghRequest.route.isResolved()) {
        resolveAll();
        routeLatLng(ghRequest, doQuery);
    } else {
        // at least one text input from user -> wait for resolve as we need the coord for routing
        $.when.apply($, resolveAll()).done(function () {
            routeLatLng(ghRequest, doQuery);
        });
    }
}

var FROM = 'from', TO = 'to';
function getToFrom(index) {
    if (index === 0)
        return FROM;
    else if (index === (ghRequest.route.size() - 1))
        return TO;
    return -1;
}

function checkInput() {
    var template = $('#pointTemplate').html(),
            len = ghRequest.route.size();

    // remove deleted points
    if ($('#locationpoints > div.pointDiv').length > len) {
        $('#locationpoints > div.pointDiv:gt(' + (len - 1) + ')').remove();
    }

    // properly unbind previously click handlers
    $("#locationpoints .pointDelete").off();

    var deleteClickHandler = function () {
        var index = $(this).parent().data('index');
        ghRequest.route.removeSingle(index);
        mapLayer.clearLayers();
        checkInput();
        routeLatLng(ghRequest, false);
    };

    // console.log("## new checkInput");
    for (var i = 0; i < len; i++) {
        var div = $('#locationpoints > div.pointDiv').eq(i);
        // console.log(div.length + ", index:" + i + ", len:" + len);
        if (div.length === 0) {
            $('#locationpoints > div.pointAdd').before(translate.nanoTemplate(template, {id: i}));
            div = $('#locationpoints > div.pointDiv').eq(i);
        }

        var toFrom = getToFrom(i);
        div.data("index", i);
        div.find(".pointFlag").attr("src",
                (toFrom === FROM) ? 'img/marker-small-green.png' :
                ((toFrom === TO) ? 'img/marker-small-red.png' : 'img/marker-small-blue.png'));
        if (len > 2) {
            div.find(".pointDelete").click(deleteClickHandler).prop('disabled', false).removeClass('ui-state-disabled');
        } else {
            div.find(".pointDelete").prop('disabled', true).addClass('ui-state-disabled');
        }

        autocomplete.showListForIndex(ghRequest, routeIfAllResolved, i);
        if (translate.isI18nIsInitialized()) {
            var input = div.find(".pointInput");
            if (i === 0)
                $(input).attr("placeholder", translate.tr("from_hint"));
            else if (i === (len - 1))
                $(input).attr("placeholder", translate.tr("to_hint"));
            else
                $(input).attr("placeholder", translate.tr("via_hint"));
        }
    }
}

function setToStart(e) {
    var latlng = e.relatedTarget.getLatLng(),
            index = ghRequest.route.getIndexByCoord(latlng);
    ghRequest.route.move(index, 0);
    routeIfAllResolved();
}

function setToEnd(e) {
    var latlng = e.relatedTarget.getLatLng(),
            index = ghRequest.route.getIndexByCoord(latlng);
    ghRequest.route.move(index, -1);
    routeIfAllResolved();
}

function setStartCoord(e) {
    ghRequest.route.set(e.latlng.wrap(), 0);
    resolveFrom();
    routeIfAllResolved();
}

function setIntermediateCoord(e) {
    var routeLayers = mapLayer.getSubLayers("route");
    var routeSegments = routeLayers.map(function (rl) {
        return {
            coordinates: rl.getLatLngs(),
            wayPoints: rl.feature.properties.snapped_waypoints.coordinates.map(function (wp) {
                return L.latLng(wp[1], wp[0]);
            })
        };
    });
    var index = routeManipulation.getIntermediatePointIndex(routeSegments, e.latlng);
    ghRequest.route.add(e.latlng.wrap(), index);
    ghRequest.do_zoom = false;
    resolveIndex(index);
    routeIfAllResolved();
}

function deleteCoord(e) {
    var latlng = e.relatedTarget.getLatLng();
    ghRequest.route.removeSingle(latlng);
    mapLayer.clearLayers();
    routeLatLng(ghRequest, false);
}

function setEndCoord(e) {
    var index = ghRequest.route.size() - 1;
    ghRequest.route.set(e.latlng.wrap(), index);
    resolveTo();
    routeIfAllResolved();
}

function routeIfAllResolved(doQuery) {
    if (ghRequest.route.isResolved()) {
        routeLatLng(ghRequest, doQuery);
        return true;
    }
    return false;
}

function setFlag(coord, index) {
    if (coord.lat) {
        var toFrom = getToFrom(index);
        // intercept openPopup
        var marker = mapLayer.createMarker(index, coord, setToEnd, setToStart, deleteCoord, ghRequest);
        marker._openPopup = marker.openPopup;
        marker.openPopup = function () {
            var latlng = this.getLatLng(),
                    locCoord = ghRequest.route.getIndexFromCoord(latlng),
                    content;
            if (locCoord.resolvedList && locCoord.resolvedList[0] && locCoord.resolvedList[0].locationDetails) {
                var address = locCoord.resolvedList[0].locationDetails;
                content = format.formatAddress(address);
                // at last update the content and update
                this._popup.setContent(content).update();
            }
            this._openPopup();
        };
        var _tempItem = {
            text: translate.tr('set_start'),
            icon: './img/marker-small-green.png',
            callback: setToStart,
            index: 1
        };
        if (toFrom === -1)
            marker.options.contextmenuItems.push(_tempItem); // because the Mixin.ContextMenu isn't initialized
        marker.on('dragend', function (e) {
            mapLayer.clearLayers();
            // inconsistent leaflet API: event.target.getLatLng vs. mouseEvent.latlng?
            var latlng = e.target.getLatLng();
            autocomplete.hide();
            ghRequest.route.getIndex(index).setCoord(latlng.lat, latlng.lng);
            resolveIndex(index);
            // do not wait for resolving and avoid zooming when dragging
            ghRequest.do_zoom = false;
            routeLatLng(ghRequest, false);
        });
    }
}

function resolveFrom() {
    return resolveIndex(0);
}

function resolveTo() {
    return resolveIndex((ghRequest.route.size() - 1));
}

function resolveIndex(index) {
    if(!ghRequest.route.getIndex(index))
        return;
    setFlag(ghRequest.route.getIndex(index), index);
    if (index === 0) {
        if (!ghRequest.to.isResolved())
            mapLayer.setDisabledForMapsContextMenu('start', true);
        else
            mapLayer.setDisabledForMapsContextMenu('start', false);
    } else if (index === (ghRequest.route.size() - 1)) {
        if (!ghRequest.from.isResolved())
            mapLayer.setDisabledForMapsContextMenu('end', true);
        else
            mapLayer.setDisabledForMapsContextMenu('end', false);
    }

    return nominatim.resolve(index, ghRequest.route.getIndex(index));
}

function resolveAll() {
    var ret = [];
    for (var i = 0, l = ghRequest.route.size(); i < l; i++) {
        ret[i] = resolveIndex(i);
    }

    if(ghRequest.isPublicTransit())
        ghRequest.setEarliestDepartureTime(
            moment($("#input_date_0").val(), 'YYYY-MM-DD HH:mm').toISOString());

    return ret;
}

function flagAll() {
    for (var i = 0, l = ghRequest.route.size(); i < l; i++) {
        setFlag(ghRequest.route.getIndex(i), i);
    }
}

function createRouteCallback(request, routeResultsDiv, urlForHistory, doZoom) {
    return function (json) {
       routeResultsDiv.html("");
       if (json.message) {
           var tmpErrors = json.message;
           console.log(tmpErrors);
           if (json.hints) {
               for (var m = 0; m < json.hints.length; m++) {
                   routeResultsDiv.append("<div class='error'>" + json.hints[m].message + "</div>");
               }
           } else {
               routeResultsDiv.append("<div class='error'>" + tmpErrors + "</div>");
           }
           return;
       }

       function createClickHandler(geoJsons, currentLayerIndex, tabHeader, oneTab, hasElevation, details) {
           return function () {

               var currentGeoJson = geoJsons[currentLayerIndex];
               mapLayer.eachLayer(function (layer) {
                   // skip markers etc
                   if (!layer.setStyle)
                       return;

                   var doHighlight = layer.feature === currentGeoJson;
                   layer.setStyle(doHighlight ? highlightRouteStyle : alternativeRouteStye);
                   if (doHighlight) {
                       if (!L.Browser.ie && !L.Browser.opera)
                           layer.bringToFront();
                   }
               });

               if (hasElevation) {
                   mapLayer.clearElevation();
                   mapLayer.addElevation(currentGeoJson, details);
               }

               headerTabs.find("li").removeClass("current");
               routeResultsDiv.find("div").removeClass("current");

               tabHeader.addClass("current");
               oneTab.addClass("current");
           };
       }

       var headerTabs = $("<ul id='route_result_tabs'/>");
       if (json.paths.length > 1) {
           routeResultsDiv.append(headerTabs);
           routeResultsDiv.append("<div class='clear'/>");
       }

       // the routing layer uses the geojson properties.style for the style, see map.js
       var defaultRouteStyle = {color: "#00cc33", "weight": 5, "opacity": 0.6};
       var highlightRouteStyle = {color: "#00cc33", "weight": 6, "opacity": 0.8};
       var alternativeRouteStye = {color: "darkgray", "weight": 6, "opacity": 0.8};
       var geoJsons = [];
       var firstHeader;

       // Create buttons to toggle between SI and imperial units.
       var createUnitsChooserButtonClickHandler = function (useMiles) {
           return function () {
               mapLayer.updateScale(useMiles);
               ghRequest.useMiles = useMiles;
               resolveAll();
               if (ghRequest.route.isResolved())
                 routeLatLng(ghRequest);
           };
       };

       if(json.paths.length > 0 && json.paths[0].points_order) {
           mapLayer.clearLayers();
           var po = json.paths[0].points_order;
           for (var i = 0; i < po.length; i++) {
               setFlag(ghRequest.route.getIndex(po[i]), i);
           }
       }

       for (var pathIndex = 0; pathIndex < json.paths.length; pathIndex++) {
           var tabHeader = $("<li>").append((pathIndex + 1) + "<img class='alt_route_img' src='img/alt_route.png'/>");
           if (pathIndex === 0)
               firstHeader = tabHeader;

           headerTabs.append(tabHeader);
           var path = json.paths[pathIndex];
           var style = (pathIndex === 0) ? defaultRouteStyle : alternativeRouteStye;

            console.log("here")
            console.log(L.geoJson(parse(path.points)))

           var geojsonFeature = {
               "type": "Feature",
               "geometry": L.geoJson(parse(path.points)),
               "properties": {
                   "style": style,
                   name: "route",
                   snapped_waypoints: path.snapped_waypoints
               }
           };

           geoJsons.push(geojsonFeature);
           mapLayer.addDataToRoutingLayer(geojsonFeature);
           var oneTab = $("<div class='route_result_tab'>");
           routeResultsDiv.append(oneTab);
           tabHeader.click(createClickHandler(geoJsons, pathIndex, tabHeader, oneTab, request.hasElevation(), path.details));

           var routeInfo = $("<div class='route_description'>");
           if (path.description && path.description.length > 0) {
               routeInfo.text(path.description);
               routeInfo.append("<br/>");
           }

           var tempDistance = translate.createDistanceString(path.distance, request.useMiles);
           var tempRouteInfo;
           if(request.isPublicTransit()) {
               var tempArrTime = moment(ghRequest.getEarliestDepartureTime())
                                       .add(path.time, 'milliseconds')
                                       .format('LT');
               if(path.transfers >= 0)
                   tempRouteInfo = translate.tr("pt_route_info", [tempArrTime, path.transfers, tempDistance]);
               else
                   tempRouteInfo = translate.tr("pt_route_info_walking", [tempArrTime, tempDistance]);
           } else {
               var tmpDuration = translate.createTimeString(path.time);
               tempRouteInfo = translate.tr("route_info", [tempDistance, tmpDuration]);
           }

           routeInfo.append(tempRouteInfo);

           var kmButton = $("<button class='plain_text_button " + (request.useMiles ? "gray" : "") + "'>");
           kmButton.text(translate.tr2("km_abbr"));
           kmButton.click(createUnitsChooserButtonClickHandler(false));

           var miButton = $("<button class='plain_text_button " + (request.useMiles ? "" : "gray") + "'>");
           miButton.text(translate.tr2("mi_abbr"));
           miButton.click(createUnitsChooserButtonClickHandler(true));

           var buttons = $("<span style='float: right;'>");
           buttons.append(kmButton);
           buttons.append('|');
           buttons.append(miButton);

           routeInfo.append(buttons);

           if (request.hasElevation()) {
               routeInfo.append(translate.createEleInfoString(path.ascend, path.descend, request.useMiles));
           }

           routeInfo.append($("<div style='clear:both'/>"));
           oneTab.append(routeInfo);

           if (path.instructions) {
               var instructions = require('./instructions.js');
               oneTab.append(instructions.create(mapLayer, path, urlForHistory, request));
           }

           var detailObj = path.details;
           if(detailObj && request.api_params.debug) {
               // detailKey, would be for example average_speed
               for (var detailKey in detailObj) {
                   var pathDetailsArr = detailObj[detailKey];
                   for (var i = 0; i < pathDetailsArr.length; i++) {
                       var pathDetailObj = pathDetailsArr[i];
                       var firstIndex = pathDetailObj[0];
                       var value = pathDetailObj[2];
                       var lngLat = path.points.coordinates[firstIndex];
                       L.marker([lngLat[1], lngLat[0]], {
                           icon: L.icon({
                               iconUrl: './img/marker-small-blue.png',
                               iconSize: [15, 15]
                           }),
                           draggable: true,
                           autoPan: true
                       }).addTo(mapLayer.getRoutingLayer()).bindPopup(detailKey + ":" + value);
                   }
               }
           }
       }
       // already select best path
       firstHeader.click();

       mapLayer.adjustMapSize();
       // TODO change bounding box on click
       var firstPath = json.paths[0];
       if (firstPath.bbox && doZoom) {
           var minLon = firstPath.bbox[0];
           var minLat = firstPath.bbox[1];
           var maxLon = firstPath.bbox[2];
           var maxLat = firstPath.bbox[3];
           var tmpB = new L.LatLngBounds(new L.LatLng(minLat, minLon), new L.LatLng(maxLat, maxLon));
           mapLayer.fitMapToBounds(tmpB);
       }

       $('.defaulting').each(function (index, element) {
           $(element).css("color", "black");
       });
   }
}

function routeLatLng(request, doQuery) {
    // do_zoom should not show up in the URL but in the request object to avoid zooming for history change
    var doZoom = request.do_zoom;
    request.do_zoom = true;
    request.removeProfileParameterIfLegacyRequest();

    var urlForHistory = request.createHistoryURL() + "&layer=" + tileLayers.activeLayerName;

    // not enabled e.g. if no cookies allowed (?)
    // if disabled we have to do the query and cannot rely on the statechange history event
    if (!doQuery && History.enabled) {
        // 2. important workaround for encoding problems in history.js
        var params = urlTools.parseUrl(urlForHistory);
        console.log(params);
        params.do_zoom = doZoom;
        // force a new request even if we have the same parameters
        params.mathRandom = Math.random();
        History.pushState(params, messages.browserTitle, urlForHistory);
        return;
    }
    var infoDiv = $("#info");
    infoDiv.empty();
    infoDiv.show();
    var routeResultsDiv = $("<div class='route_results'/>");
    infoDiv.append(routeResultsDiv);

    mapLayer.clearElevation();
    mapLayer.clearLayers();
    flagAll();

    mapLayer.setDisabledForMapsContextMenu('intermediate', false);

    $("#profiles button").removeClass("selectprofile");
    var buttonToSelectId = request.getProfile();
    // for legacy requests this might be undefined then we just do not select anything
    if (buttonToSelectId)
    $("button#" + buttonToSelectId.toLowerCase()).addClass("selectprofile");

    var streetRouteRequest = new Router.StreetRouteRequest();
    streetRouteRequest.setProfile(request.getProfile());
    var from = new Router.Point();
    from.setLat(request.from.lat);
    from.setLon(request.from.lng);
    var to = new Router.Point();
    to.setLat(request.to.lat);
    to.setLon(request.to.lng);
    streetRouteRequest.addPoints(from);
    streetRouteRequest.addPoints(to);
    streetRouteRequest.setAlternateRouteMaxPaths($('#alt_route_max_paths').val());
    streetRouteRequest.setAlternateRouteMaxWeightFactor($('#alt_route_max_weight_factor').val());
    streetRouteRequest.setAlternateRouteMaxShareFactor($('#alt_route_max_share_factor').val());
    streetRouteRequest.setReturnFullPathDetails(true);
    console.log(streetRouteRequest.toObject());

    var router = new Router.RouterClient('/api');
    router.routeStreetMode(streetRouteRequest, null, function(err, response) {
        if (err) {
            console.log("Error handling request!")
            console.log(err)
            console.log(streetRouteRequest);
        } else {
            console.log(response.toObject());

            routeResultsDiv.html("");

            function createClickHandler(geoJsons, currentLayerIndex, tabHeader, oneTab, hasElevation, details) {
                return function () {

                   var currentGeoJson = geoJsons[currentLayerIndex];
                   mapLayer.eachLayer(function (layer) {
                       // skip markers etc
                       if (!layer.setStyle)
                           return;

                       var doHighlight = layer.feature === currentGeoJson;
                       layer.setStyle(doHighlight ? highlightRouteStyle : alternativeRouteStye);
                       if (doHighlight) {
                           if (!L.Browser.ie && !L.Browser.opera)
                               layer.bringToFront();
                       }
                   });

                   if (hasElevation) {
                       mapLayer.clearElevation();
                       mapLayer.addElevation(currentGeoJson, details);
                   }

                   headerTabs.find("li").removeClass("current");
                   routeResultsDiv.find("div").removeClass("current");

                   tabHeader.addClass("current");
                   oneTab.addClass("current");
                };
            }
            var headerTabs = $("<ul id='route_result_tabs'/>");
            if (response.getPathsList().length > 1) {
               routeResultsDiv.append(headerTabs);
               routeResultsDiv.append("<div class='clear'/>");
            }

            // the routing layer uses the geojson properties.style for the style, see map.js
            var defaultRouteStyle = {color: "#00cc33", "weight": 5, "opacity": 0.6};
            var highlightRouteStyle = {color: "#00cc33", "weight": 6, "opacity": 0.8};
            var alternativeRouteStye = {color: "darkgray", "weight": 6, "opacity": 0.8};
            var geoJsons = [];
            var firstHeader;

            // Create buttons to toggle between SI and imperial units.
            var createUnitsChooserButtonClickHandler = function (useMiles) {
               return function () {
                   mapLayer.updateScale(useMiles);
                   ghRequest.useMiles = useMiles;
                   resolveAll();
                   if (ghRequest.route.isResolved())
                     routeLatLng(ghRequest);
               };
            };

            for (var pathIndex = 0; pathIndex < response.getPathsList().length; pathIndex++) {
                var tabHeader = $("<li>").append((pathIndex + 1) + "<img class='alt_route_img' src='img/alt_route.png'/>");
                if (pathIndex === 0)
                   firstHeader = tabHeader;

                headerTabs.append(tabHeader);
                var path = response.getPathsList()[pathIndex];
                var style = (pathIndex === 0) ? defaultRouteStyle : alternativeRouteStye;

                console.log(wkt.parse(path.getPoints()))
                var geojsonFeature = {
                   "type": "Feature",
                   "geometry": wkt.parse(path.getPoints()),
                   "properties": {
                       "style": style,
                       name: "route",
                   }
                };

                geoJsons.push(geojsonFeature);
                mapLayer.addDataToRoutingLayer(geojsonFeature);
                var oneTab = $("<div class='route_result_tab'>");
                routeResultsDiv.append(oneTab);
                tabHeader.click(createClickHandler(geoJsons, pathIndex, tabHeader, oneTab, request.hasElevation(), path.details));

                var routeInfo = $("<div class='route_description'>");

                var kmButton = $("<button class='plain_text_button " + (request.useMiles ? "gray" : "") + "'>");
                kmButton.text(translate.tr2("km_abbr"));
                kmButton.click(createUnitsChooserButtonClickHandler(false));

                var miButton = $("<button class='plain_text_button " + (request.useMiles ? "" : "gray") + "'>");
                miButton.text(translate.tr2("mi_abbr"));
                miButton.click(createUnitsChooserButtonClickHandler(true));

                var buttons = $("<span style='float: right;'>");
                buttons.append(kmButton);
                buttons.append('|');
                buttons.append(miButton);

                routeInfo.append(buttons);

                routeInfo.append($("<div style='clear:both'/>"));
                oneTab.append(routeInfo);
           }
           // already select best path
           firstHeader.click();

           mapLayer.adjustMapSize();

           $('.defaulting').each(function (index, element) {
               $(element).css("color", "black");
           });
        }
    })
    // routeResultsDiv.html('<img src="img/indicator.gif"/> Search Route ...');
    // request.doRequest(urlForAPI, createRouteCallback(request, routeResultsDiv, urlForHistory, doZoom));
}

function mySubmit() {
    var fromStr,
            toStr,
            viaStr,
            allStr = [],
            inputOk = true;
    var location_points = $("#locationpoints > div.pointDiv > input.pointInput");
    var len = location_points.size;
    $.each(location_points, function (index) {
        if (index === 0) {
            fromStr = $(this).val();
            if (fromStr !== translate.tr("from_hint") && fromStr !== "")
                allStr.push(fromStr);
            else
                inputOk = false;
        } else if (index === (len - 1)) {
            toStr = $(this).val();
            if (toStr !== translate.tr("to_hint") && toStr !== "")
                allStr.push(toStr);
            else
                inputOk = false;
        } else {
            viaStr = $(this).val();
            if (viaStr !== translate.tr("via_hint") && viaStr !== "")
                allStr.push(viaStr);
            else
                inputOk = false;
        }
    });
    if (!inputOk) {
        // TODO print warning
        return;
    }
    if (fromStr === translate.tr("from_hint")) {
        // no special function
        return;
    }
    if (toStr === translate.tr("to_hint")) {
        // lookup area
        ghRequest.from.setStr(fromStr);
        $.when(resolveFrom()).done(function () {
            mapLayer.focus(ghRequest.from, null, 0);
        });
        return;
    }
    // route!
    if (inputOk)
        resolveCoords(allStr);
}

function isProduction() {
    return host.indexOf("graphhopper.com") > 0;
}

module.exports.setFlag = setFlag;
