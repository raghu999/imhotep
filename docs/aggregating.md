---
layout: default
title: Aggregating Results
permalink: /docs/aggregating/
---

Use the optional **group by** filter to group documents and retrieve aggregated stats. Separate each filter from another with a comma. If you leave this control empty, IQL places all documents into a single group and returns one row.

The following filters are available:
<table>
  <tr>
    <th>Filter</th>
    <th>Syntax</th>
    <th>Examples</th>
  </tr>
  <tr>
    <td valign="top">Simple grouping by field name</td>
    <td valign="top">field name</td>
    <td valign="top">`country`</td>
  </tr>
  <tr>
    <td valign="top">Limit the number of groups (top/bottom K).</td>
    <td valign="top">field[number] <br> field[bottomNumber by metric]</td>
    <td valign="top">`country[5]` returns the top 5 countries by count.<br>`country[bottom 5 by clicks]` specifies the metric by which to order and uses the bottom countries instead of the top.</td>
  </tr>
  <tr>
    <td valign="top">Exclude results for fields in which your specified metrics equal 0.</td>
    <td valign="top">field[]</td>
    <td valign="top">`country[]` returns results for all countries with metrics that are not zero.<br> `country, group[]` returns results for groups that exist in each country.<br> `country, group` returns a full cross product of countries and groups, including groups for countries where the group is not present and all metrics are 0.</td>
  </tr>
<tr>
    <td valign="top">Group your data into buckets by ranges you define. <br><br>This construction automatically determines the size of the buckets and is useful for graphs. <br><br>The values for min, min2, max, max2, interval and interval2 are numbers. </td>
    <td valign="top">buckets(metric, min, max, interval</td>
    <td valign="top">`buckets(accountbalance, 10, 100, 20)`</td>
  </tr>
<tr>
    <td valign="top">For multiple group-bys with buckets, include all bucket definitions in one statement.</td>
    <td valign="top">buckets(metricX, min, max, interval metricY, min2, max2, interval2)</td>
    <td valign="top">`buckets(time(1d), 1, 10, 1, accountbalance 10, 100, 20)`</td>
  </tr>
<tr>
    <td valign="top">Group your data into time buckets. The bucket size uses the same syntax as the relative values for the start and end values in the **timerange** filter: s, m, h, d or w.</td>
    <td valign="top">time(bucketSize)</td>
    <td valign="top">`time(1h)` groups data into buckets, each of which includes data from 1 hour.</td>
  </tr>
<tr>
    <td valign="top">You can also define the bucket size as an absolute value.</td>
    <td valign="top">Nb</td>
    <td valign="top">`time(3b)` groups data into 3 buckets, each of which includes data from one-third of the given time range.</td>
  </tr>
<tr>
    <td valign="top">IN construction for including more than one term. Using the IN construction in the **group by** filter is the same as using the IN construction in the **where** filter and then grouping by field name.</td>
    <td valign="top">field in (term,term)<br>field in ("term",term) <br>field not in (term,term) </td>
    <td valign="top">`country in (canada,us)` <br>`country in ("great britain",deutschland)` <br>`country not in (france,canada)` </td>
  </tr>

</table>

