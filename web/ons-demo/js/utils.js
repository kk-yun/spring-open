function parseURLParameters() {
	var parameters = {};

	var search = location.href.split('?')[1];
	if (search) {
		search.split('&').forEach(function (param) {
			var key = param.split('=')[0];
			var value = param.split('=')[1];
			parameters[key] = decodeURIComponent(value);
		});
	}

	return parameters;
}

function findLink(model, dpid) {
	var links = [];
	model.links.forEach(function (link) {
		if (link['src-switch'] == dpid || link['dst-switch'] == dpid) {
			links.push(link);
		}
	});
	return links;
}