(function($, cloudStack) {
  /**
   * Instance wizard
   */
  cloudStack.instanceWizard = function(args) {
    return function(listViewArgs) {
      var instanceWizard = function(data) {
        var $wizard = $('#template').find('div.instance-wizard').clone();
        var $progress = $wizard.find('div.progress ul li');
        var $steps = $wizard.find('div.steps').children().hide();
        var $diagramParts = $wizard.find('div.diagram').children().hide();
        var $form = $wizard.find('form');

        $form.validate();

        // Close instance wizard
        var close = function() {
          $wizard.dialog('destroy');
          $('div.overlay').fadeOut(function() { $('div.overlay').remove(); });
        };

        // Save instance and close wizard
        var completeAction = function() {
          args.complete({
            // Populate data
            data: cloudStack.serializeForm($form),
            response: {
              success: function(args) {
                listViewArgs.complete({
                  _custom: args._custom,
                  messageArgs: cloudStack.serializeForm($form)
                });
                close();
              }
            }
          });
        };

        var makeSelects = function(name, data, fields, options) {
          var $selects = $('<div>');

          $(data).each(function() {
            $selects.append(
              $('<div>').addClass('select')
                .append(
                  $('<input>')
                    .attr({
                      type: (function(type) {
                        return type ? type : 'radio';
                      })(options ? options.type : null),
                      name: name,
                      value: this[fields.id]
                    })
                )
                .append(
                  $('<div>').addClass('select-desc')
                    .append($('<div>').addClass('name').html(this[fields.name]))
                    .append($('<div>').addClass('desc').html(this[fields.desc]))
                )
            );
          });

          cloudStack.evenOdd($selects, 'div.select', {
            even: function($elem) {
              $elem.addClass('even');
            },
            odd: function($elem) {
              $elem.addClass('odd');
            }
          });

          return $selects.children();
        };

        var dataProvider = function(step, providerArgs, callback) {
          // Call appropriate data provider
          args.steps[step - 1]($.extend(providerArgs, {
            currentData: cloudStack.serializeForm($form)
          }));
        };

        var dataGenerators = {
          setup: function($step, formData) {
            var originalValues = function(formData) {
              $step.find('select').val(
                formData.zoneid
              );

              $step.find('input[type=radio]').filter(function() {
                return $(this).val() == formData['select-template'];
              }).click();
            };

            return {
              response: {
                success: function(args) {
                  // Zones
                  $(args.data.zones).each(function() {
                    $step.find('.select-zone select').append(
                      $('<option>')
                        .attr({ value: this.id })
                        .html(this.name)
                    );
                  });

                  originalValues(formData);
                }
              }
            };
          },

          'select-iso': function($step, formData) {
            var originalValues = function(formData) {
              $step.find('input[type=radio]').filter(function() {
                return $(this).val() == formData.templateid;
              }).click();
            };

            return {
              response: {
                success: function(args) {
                  var makeIsos = function(type, append) {
                    append(
                      makeSelects('templateid', args.data.isos[type], {
                        name: 'name',
                        desc: 'displaytext',
                        id: 'id'
                      })
                    );
                  };

                  // Featured ISOs
                  $(
                    [
                      ['featured', 'instance-wizard-featured-isos'],
                      ['community', 'instance-wizard-community-isos'],
                      ['mine', 'instance-wizard-my-isos']
                    ]
                  ).each(function() {
                    var item = this;
                    var $selectContainer = $wizard.find('#' + item[1]).find('.select-container');

                    makeIsos(item[0], function($elem) {
                      $selectContainer.append($elem);
                    });
                  });

                  originalValues(formData);
                }
              }
            };
          },

          'service-offering': function($step, formData) {
            var originalValues = function(formData) {
              $step.find('input[type=radio]').filter(function() {
                return $(this).val() == formData.serviceofferingid;
              }).click();
            };

            return {
              response: {
                success: function(args) {
                  $step.find('.content .select-container').append(
                    makeSelects('serviceofferingid', args.data.serviceOfferings, {
                      name: 'name',
                      desc: 'displaytext',
                      id: 'id'
                    })
                  );

                  originalValues(formData);
                }
              }
            };
          },

          'data-disk-offering': function($step, formData) {
            var originalValues = function(formData) {
              var $targetInput = $step.find('input[type=radio]').filter(function() { 
                return $(this).val() == formData.diskofferingid;
              }).click();
            };

            $step.find('.section.custom-size').hide();

            return {
              response: {
                success: function(args) {
                  $step.removeClass('custom-disk-size');
                  if (args.required) {
                    $step.find('.section.no-thanks').hide();
                    $step.addClass('required');
                  } else {
                    $step.find('.section.no-thanks').show();
                    $step.removeClass('required');
                  }

                  $step.find('.content .select-container').append(
                    makeSelects('diskofferingid', args.data.diskOfferings, {
                      id: 'id',
                      name: 'name',
                      desc: 'displaytext'
                    })
                  );

                  $step.find('input[type=radio]').bind('change', function() {
                    var $target = $(this);
                    var val = $target.val();
                    var item = $.grep(args.data.diskOfferings, function(elem) {
                      return elem.id == val;
                    })[0];

                    if (!item) return true;

                    var custom = item[args.customFlag];

                    if (custom) {
                      $step.find('.section.custom-size').show();
                      $step.addClass('custom-disk-size');
                      $target.closest('.select-container').scrollTop(
                        $target.position().top
                      );
                    } else {
                      $step.find('.section.custom-size').hide();
                      $step.removeClass('custom-disk-size');
                    }

                    return true;
                  });

                  originalValues(formData);
                }
              }
            };
          },

          'network': function($step, formData) {
            var originalValues = function(formData) {
              // Default networks
              $step.find('input[name=default-network]').filter(function() {
                return $(this).val() == formData['default-network'];
              }).click();

              // Optional networks
              var selectedOptionalNetworks = [];

              if ($.isArray(formData['optional-networks'])) {
                $(formData['optional-networks']).each(function() {
                  selectedOptionalNetworks.push(this);
                });
              } else {
                selectedOptionalNetworks.push(formData['optional-networks']);
              }

              var $checkboxes = $step.find('input[name=optional-networks]');
              $(selectedOptionalNetworks).each(function() {
                var networkID = this;
                $checkboxes.filter(function() {
                  return $(this).val() == networkID;
                }).attr('checked', 'checked');
              });
            };

            return {
              response: {
                success: function(args) {
                  // Show relevant conditional sub-step if present
                  $step.find('.wizard-step-conditional').hide();
                  if (args.type) {
                    $step.find('.wizard-step-conditional').filter(function() {
                      return $(this).hasClass(args.type);
                    }).show();
                  } else {
                    $step.find('.select-network').show();
                  }

                  // Default network
                  $step.find('.default-network .select-container').append(
                    makeSelects('default-network', args.data.defaultNetworks, {
                      name: 'name',
                      desc: 'displaytext',
                      id: 'id'
                    })
                  );

                  // Optional networks
                  $step.find('.optional-networks .select-container').append(
                    makeSelects('optional-networks', args.data.optionalNetworks, {
                      name: 'name',
                      desc: 'displaytext',
                      id: 'id'
                    }, {
                      type: 'checkbox'
                    })
                  );

                  // Security groups (alt. page)
                  $step.find('.security-groups .select-container').append(
                    makeSelects('security-groups', args.data.securityGroups, {
                      name: 'name',
                      desc: 'description',
                      id: 'id'
                    }, {
                      type: 'checkbox'
                    })
                  );

                  originalValues(formData);
                }
              }
            };
          },

          'review': function($step) {
            return {
              response: {
                success: function(args) {
                }
              }
            };
          }
        };

        // Go to specified step in wizard,
        // updating nav items and diagram
        var showStep = function(index) {
          var targetIndex = index - 1;

          if (index <= 1) targetIndex = 0;
          if (targetIndex == $steps.size()) {
            completeAction();
            return;
          }

          var $targetStep = $($steps.hide()[targetIndex]).show();
          var stepID = $targetStep.attr('wizard-step-id');
          var formData = cloudStack.serializeForm($form);

          if (!$targetStep.hasClass('review')) { // Review row content is not autogenerated
            $targetStep.find('.select-container div, option').remove();
          }

          dataProvider(
            index,
            dataGenerators[stepID](
              $targetStep,
              formData
            )
          );

          // Show launch vm button if last step
          var $nextButton = $wizard.find('.button.next');
          $nextButton.find('span').html('Next');
          $nextButton.removeClass('final');
          if ($targetStep.hasClass('review')) {
            $nextButton.find('span').html('Launch VM');
            $nextButton.addClass('final');
          }

          // Update progress bar
          var $targetProgress = $progress.removeClass('active').filter(function() {
            return $(this).index() <= targetIndex;
          }).toggleClass('active');

          // Update diagram; show/hide as necessary
          $diagramParts.filter(function() {
            return $(this).index() <= targetIndex;
          }).fadeIn('slow');
          $diagramParts.filter(function() {
            return $(this).index() > targetIndex;
          }).fadeOut('slow');


          setTimeout(function() {
            if (!$targetStep.find('input[type=radio]:checked').size()) {
              $targetStep.find('input[type=radio]:first').click();
            }
          }, 50);
        };

        // Events
        $wizard.click(function(event) {
          var $target = $(event.target);

          // Next button
          if ($target.closest('div.button.next').size()) {
            if (!$form.valid()) return false;

            showStep($steps.filter(':visible').index() + 2);

            return false;
          }

          // Previous button
          if ($target.closest('div.button.previous').size()) {
            showStep($steps.filter(':visible').index());

            return false;
          }

          // Close button
          if ($target.closest('div.button.cancel').size()) {
            close();

            return false;
          }

          // Edit link
          if ($target.closest('div.edit').size()) {
            var $edit = $target.closest('div.edit');

            showStep($edit.find('a').attr('href'));

            return false;
          }

          return true;
        });

        showStep(1);

        // Setup tabs and slider
        $wizard.find('.tab-view').tabs();
        $wizard.find('.slider').slider({
          min: 1,
          max: 100,
          start: function(event) {
            $wizard.find('div.data-disk-offering div.custom-size input[type=radio]').click();
          },
          slide: function(event, ui) {
            $wizard.find('div.data-disk-offering div.custom-size input[type=text]').val(
              ui.value
            );
          }
        });

        return $wizard.dialog({
          title: 'Add instance',
          width: 800,
          height: 570,
          zIndex: 5000
        })
          .closest('.ui-dialog').overlay();
      };

      instanceWizard(args);
    };
  };
})(jQuery, cloudStack);